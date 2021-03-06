package se.jiderhamn.classloader.leak.prevention;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This class helps prevent classloader leaks.
 * @author Mattias Jiderhamn
 */
@SuppressWarnings("WeakerAccess")
public class ClassLoaderLeakPreventor {
  
  private static final ProtectionDomain[] NO_DOMAINS = new ProtectionDomain[0];

  private static final AccessControlContext NO_DOMAINS_ACCESS_CONTROL_CONTEXT = new AccessControlContext(NO_DOMAINS);
  
  private final Field java_security_AccessControlContext$combiner;
  
  private final Field java_security_AccessControlContext$parent;
  
  private final Field java_security_AccessControlContext$privilegedContext;

  /** 
   * {@link ClassLoader} to be used when invoking the {@link PreClassLoaderInitiator}s.
   * This will normally be the {@link ClassLoader#getSystemClassLoader()}, but could be any other framework or 
   * app server classloader. Normally, but not necessarily, a parent of {@link #classLoader}.
   */
  private final ClassLoader leakSafeClassLoader;
  
  /** The {@link ClassLoader} we want to avoid leaking */
  private final ClassLoader classLoader;
  
  private final Logger logger;
  
  private final Collection<PreClassLoaderInitiator> preClassLoaderInitiators;
  
  private final Collection<ClassLoaderPreMortemCleanUp> cleanUps;
  
  /** {@link DomainCombiner} that filters any {@link ProtectionDomain}s loaded by our classloader */
  private final DomainCombiner domainCombiner;

  public ClassLoaderLeakPreventor(ClassLoader leakSafeClassLoader, ClassLoader classLoader, Logger logger,
                           Collection<PreClassLoaderInitiator> preClassLoaderInitiators,
                           Collection<ClassLoaderPreMortemCleanUp> cleanUps) {
    this.leakSafeClassLoader = leakSafeClassLoader;
    this.classLoader = classLoader;
    this.logger = logger;
    this.preClassLoaderInitiators = preClassLoaderInitiators;
    this.cleanUps = cleanUps;

    this.domainCombiner = createDomainCombiner();

    // Reflection inits
    java_security_AccessControlContext$combiner = findField(AccessControlContext.class, "combiner");
    java_security_AccessControlContext$parent = findField(AccessControlContext.class, "parent");
    java_security_AccessControlContext$privilegedContext = findField(AccessControlContext.class, "privilegedContext");

  }
  
  /** Invoke all the registered {@link PreClassLoaderInitiator}s in the {@link #leakSafeClassLoader} */
  public void runPreClassLoaderInitiators() {
    doInLeakSafeClassLoader(new Runnable() {
      @Override
      public void run() {
        for(PreClassLoaderInitiator preClassLoaderInitiator : preClassLoaderInitiators) {
          preClassLoaderInitiator.doOutsideClassLoader(ClassLoaderLeakPreventor.this);
        }
      }
    });
  }
  
  /**
   * Perform action in the provided ClassLoader (normally system ClassLoader, that may retain references to the 
   * {@link Thread#contextClassLoader}. 
   * The method is package protected so that it can be called from test cases. TODO Still needed?
   * The motive for the custom {@link AccessControlContext} is to avoid spawned threads from inheriting all the 
   * {@link java.security.ProtectionDomain}s of the running code, since that may include the classloader we want to 
   * avoid leaking. This however means the {@link AccessControlContext} will have a {@link DomainCombiner} referencing the 
   * classloader, which will be taken care of in {@link #stopThreads()} TODO!!!.
   */
   protected void doInLeakSafeClassLoader(final Runnable runnable) {
     final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
     
     try {
       Thread.currentThread().setContextClassLoader(leakSafeClassLoader);
 
       // Use doPrivileged() not to perform secured actions, but to avoid threads spawned inheriting the 
       // AccessControlContext of the current thread, since among the ProtectionDomains there will be one
       // (the top one) whose classloader is the web app classloader
       AccessController.doPrivileged(new PrivilegedAction<Object>() {
         @Override
         public Object run() {
           runnable.run();
           return null; // Nothing to return
         }
       }, createAccessControlContext());
     }
     finally {
       // Reset original classloader
       Thread.currentThread().setContextClassLoader(contextClassLoader);
     }
   }
   
   /** 
    * Create {@link AccessControlContext} that is used in {@link #doInLeakSafeClassLoader(Runnable)}.
    * The motive is to avoid spawned threads from inheriting all the {@link java.security.ProtectionDomain}s of the 
    * running code, since that will include the web app classloader.
    */
   protected AccessControlContext createAccessControlContext() {
     try { // Try the normal way
       return new AccessControlContext(NO_DOMAINS_ACCESS_CONTROL_CONTEXT, domainCombiner);
     }
     catch (SecurityException e) { // createAccessControlContext not granted
       try { // Try reflection
         Constructor<AccessControlContext> constructor = 
             AccessControlContext.class.getDeclaredConstructor(ProtectionDomain[].class, DomainCombiner.class);
         constructor.setAccessible(true);
         return constructor.newInstance(NO_DOMAINS, domainCombiner);
       }
       catch (Exception e1) {
         logger.error("createAccessControlContext not granted and AccessControlContext could not be created via reflection");
         return AccessController.getContext();
       }
     }
   } 
   
   /** {@link DomainCombiner} that filters any {@link ProtectionDomain}s loaded by our classloader */
   private DomainCombiner createDomainCombiner() {
     return new DomainCombiner() {
       @Override
       public ProtectionDomain[] combine(ProtectionDomain[] currentDomains, ProtectionDomain[] assignedDomains) {
         if(assignedDomains != null && assignedDomains.length > 0) {
           logger.error("Unexpected assignedDomains - please report to developer of this library!");
         }
 
         // Keep all ProtectionDomain not involving the web app classloader 
         final List<ProtectionDomain> output = new ArrayList<ProtectionDomain>();
         for(ProtectionDomain protectionDomain : currentDomains) {
           if(protectionDomain.getClassLoader() == null ||
               ! isClassLoaderOrChild(protectionDomain.getClassLoader())) {
             output.add(protectionDomain);
           }
         }
         return output.toArray(new ProtectionDomain[output.size()]);
       }
     };
   }  
  
  /** 
   * Recursively unset our custom {@link DomainCombiner} (loaded in the web app) from the {@link AccessControlContext} 
   * and any parents or privilegedContext thereof.
   * TODO: Consider extracting to {@link ClassLoaderPreMortemCleanUp}
   */
  public void removeDomainCombiner(Thread thread, AccessControlContext accessControlContext) {
    if(accessControlContext != null) {
      if(getFieldValue(java_security_AccessControlContext$combiner, accessControlContext) == this.domainCombiner) {
        warn(AccessControlContext.class.getSimpleName() + " of thread " + thread + " used custom combiner - unsetting");
        try {
          java_security_AccessControlContext$combiner.set(accessControlContext, null);
        }
        catch (Exception e) {
          error(e);
        }
      }
      
      // Recurse
      if(java_security_AccessControlContext$parent != null) {
        removeDomainCombiner(thread, (AccessControlContext) getFieldValue(java_security_AccessControlContext$parent, accessControlContext));
      }
      if(java_security_AccessControlContext$privilegedContext != null) {
        removeDomainCombiner(thread, (AccessControlContext) getFieldValue(java_security_AccessControlContext$privilegedContext, accessControlContext));
      }
    }
  }
  
  
  /** Invoke all the registered {@link ClassLoaderPreMortemCleanUp}s */
  public void runCleanUps() {
    for(ClassLoaderPreMortemCleanUp cleanUp : cleanUps) {
      cleanUp.cleanUp(this);
    }
  }
  
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Utility methods

  public ClassLoader getClassLoader() {
    return classLoader;
  }

  /**
   * Get {@link ClassLoader} to be used when invoking the {@link PreClassLoaderInitiator}s.
   * This will normally be the {@link ClassLoader#getSystemClassLoader()}, but could be any other framework or 
   * app server classloader. Normally, but not necessarily, a parent of {@link #classLoader}.
   */
  public ClassLoader getLeakSafeClassLoader() {
    return leakSafeClassLoader;
  }

  /** Test if provided object is loaded by {@link #classLoader} */
  public boolean isLoadedInClassLoader(Object o) {
    return (o instanceof Class) && isLoadedByClassLoader((Class<?>)o) || // Object is a java.lang.Class instance 
        o != null && isLoadedByClassLoader(o.getClass());
  }

  /** Test if provided class is loaded wby {@link #classLoader} */
  public boolean isLoadedByClassLoader(Class<?> clazz) {
    return clazz != null && isClassLoaderOrChild(clazz.getClassLoader());
  }

  /** Test if provided ClassLoader is the {@link #classLoader}, or a child thereof */
  public boolean isClassLoaderOrChild(ClassLoader cl) {
    while(cl != null) {
      if(cl == classLoader)
        return true;
      
      cl = cl.getParent();
    }

    return false;
  }

  public boolean isThreadInClassLoader(Thread thread) {
    return isLoadedInClassLoader(thread) || // Custom Thread class in classloader
       isClassLoaderOrChild(thread.getContextClassLoader()); // Running in classloader
  }
  
  public <E> E getStaticFieldValue(Class<?> clazz, String fieldName) {
    Field staticField = findField(clazz, fieldName);
    return (staticField != null) ? (E) getStaticFieldValue(staticField) : null;
  }

  public <E> E getStaticFieldValue(String className, String fieldName) {
    return (E) getStaticFieldValue(className, fieldName, false);
  }
  
  public <E> E getStaticFieldValue(String className, String fieldName, boolean trySystemCL) {
    Field staticField = findFieldOfClass(className, fieldName, trySystemCL);
    return (staticField != null) ? (E) getStaticFieldValue(staticField) : null;
  }
  
  public Field findFieldOfClass(String className, String fieldName) {
    return findFieldOfClass(className, fieldName, false);
  }
  
  public Field findFieldOfClass(String className, String fieldName, boolean trySystemCL) {
    Class<?> clazz = findClass(className, trySystemCL);
    if(clazz != null) {
      return findField(clazz, fieldName);
    }
    else
      return null;
  }
  
  public Class<?> findClass(String className) {
    return findClass(className, false);
  }
  
  public Class<?> findClass(String className, boolean trySystemCL) {
    try {
      return Class.forName(className);
    }
//    catch (NoClassDefFoundError e) {
//      // Silently ignore
//      return null;
//    }
    catch (ClassNotFoundException e) {
      if (trySystemCL) {
        try {
          return Class.forName(className, true, ClassLoader.getSystemClassLoader());
        } catch (ClassNotFoundException e1) {
          // Silently ignore
          return null;
        }
      }
      // Silently ignore
      return null;
    }
    catch (Exception ex) { // Example SecurityException
      warn(ex);
      return null;
    }
  }
  
  public Field findField(Class<?> clazz, String fieldName) {
    if(clazz == null)
      return null;

    try {
      final Field field = clazz.getDeclaredField(fieldName);
      field.setAccessible(true); // (Field is probably private) 
      return field;
    }
    catch (NoSuchFieldException ex) {
      // Silently ignore
      return null;
    }
    catch (Exception ex) { // Example SecurityException
      warn(ex);
      return null;
    }
  }
  
  public <T> T getStaticFieldValue(Field field) {
    try {
      if(! Modifier.isStatic(field.getModifiers())) {
        warn(field.toString() + " is not static");
        return null;
      }
      
      return (T) field.get(null);
    }
    catch (Exception ex) {
      warn(ex);
      // Silently ignore
      return null;
    }
  }
  
  public <T> T getFieldValue(Object obj, String fieldName) {
    final Field field = findField(obj.getClass(), fieldName);
    return (T) getFieldValue(field, obj);
  }
  
  public <T> T getFieldValue(Field field, Object obj) {
    try {
      return (T) field.get(obj);
    }
    catch (Exception ex) {
      warn(ex);
      // Silently ignore
      return null;
    }
  }
  
  public void setFinalStaticField(Field field, Object newValue) {
    // Allow modification of final field 
    try {
      Field modifiersField = Field.class.getDeclaredField("modifiers");
      modifiersField.setAccessible(true);
      modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    }
    catch (NoSuchFieldException e) {
      warn("Unable to get 'modifiers' field of java.lang.Field");
    }
    catch (IllegalAccessException e) {
      warn("Unable to set 'modifiers' field of java.lang.Field");
    }
    catch (Throwable t) {
      warn(t);
    }

    // Update the field
    try {
      field.set(null, newValue);
    }
    catch (Throwable e) {
      error("Error setting value of " + field + " to " + newValue);
    }
  }
  
  public Method findMethod(String className, String methodName, Class... parameterTypes) {
    Class<?> clazz = findClass(className);
    if(clazz != null) {
      return findMethod(clazz, methodName, parameterTypes);
    }
    else 
      return null;
  }
  
  public Method findMethod(Class<?> clazz, String methodName, Class... parameterTypes) {
    if(clazz == null)
      return null;

    try {
      final Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
      method.setAccessible(true);
      return method;
    }
    catch (NoSuchMethodException ex) {
      warn(ex);
      // Silently ignore
      return null;
    }
  }

  /** Get a Collection with all Threads. 
   * This method is heavily inspired by org.apache.catalina.loader.WebappClassLoader.getThreads() */
  public Collection<Thread> getAllThreads() {
    // This is some orders of magnitude slower...
    // return Thread.getAllStackTraces().keySet();
    
    // Find root ThreadGroup
    ThreadGroup tg = Thread.currentThread().getThreadGroup();
    while(tg.getParent() != null)
      tg = tg.getParent();
    
    // Note that ThreadGroup.enumerate() silently ignores all threads that does not fit into array
    int guessThreadCount = tg.activeCount() + 50;
    Thread[] threads = new Thread[guessThreadCount];
    int actualThreadCount = tg.enumerate(threads);
    while(actualThreadCount == guessThreadCount) { // Map was filled, there may be more
      guessThreadCount *= 2;
      threads = new Thread[guessThreadCount];
      actualThreadCount = tg.enumerate(threads);
    }
    
    // Filter out nulls
    final List<Thread> output = new ArrayList<Thread>();
    for(Thread t : threads) {
      if(t != null) {
        output.add(t);
      }
    }
    return output;
  }
  
  /**
   * Override this method if you want to customize how we determine if we're running in
   * JBoss WildFly (a.k.a JBoss AS).
   */
  public boolean isJBoss() {
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    
    try {
      // If package org.jboss is found, we may be running under JBoss
      return (contextClassLoader.getResource("org/jboss") != null);
    }
    catch(Exception ex) {
      return false;
    }
  }
  
  /**
   * Are we running in the Oracle/Sun Java Runtime Environment?
   * Override this method if you want to customize how we determine if this is a Oracle/Sun
   * Java Runtime Environment.
   */
  public boolean isOracleJRE() {
    String javaVendor = System.getProperty("java.vendor");
    
    return javaVendor.startsWith("Oracle") || javaVendor.startsWith("Sun");
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Delegate methods for Logger


  public void debug(String msg) {
    logger.debug(msg);
  }

  public void warn(Throwable t) {
    logger.warn(t);
  }

  public void error(Throwable t) {
    logger.error(t);
  }

  public void warn(String msg) {
    logger.warn(msg);
  }

  public void error(String msg) {
    logger.error(msg);
  }

  public void info(String msg) {
    logger.info(msg);
  }
}
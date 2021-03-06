/*
   Copyright 2012 Mattias Jiderhamn

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package se.jiderhamn.classloader.leak.prevention;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessControlContext;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import se.jiderhamn.classloader.leak.prevention.cleanup.*;
import se.jiderhamn.classloader.leak.prevention.preinit.*;

import static se.jiderhamn.classloader.leak.prevention.cleanup.ShutdownHookCleanUp.SHUTDOWN_HOOK_WAIT_MS_DEFAULT;

/**
 * This class helps prevent classloader leaks.
 * <h1>Basic setup</h1>
 * <p>Activate protection by adding this class as a context listener
 * in your <code>web.xml</code>, like this:</p>
 * <pre>
 *  &lt;listener&gt;
 *     &lt;listener-class&gt;se.jiderhamn.classloader.leak.prevention.ClassLoaderLeakPreventor&lt;/listener-class&gt;
 *  &lt;/listener&gt; 
 * </pre>
 * 
 * You should usually declare this <code>listener</code> before any other listeners, to make it "outermost".
 *
 * <h1>Configuration</h1>
 * The context listener has a number of settings that can be configured with context parameters in <code>web.xml</code>,
 * i.e.:
 * 
 * <pre>
 *   &lt;context-param&gt;
 *     &lt;param-name&gt;ClassLoaderLeakPreventor.stopThreads&lt;/param-name&gt;
 *     &lt;param-value&gt;false&lt;/param-value&gt;
 *   &lt;/context-param&gt;
 * </pre>
 * 
 * The available settings are
 * <table border="1">
 *   <tr>
 *     <th>Parameter name</th>
 *     <th>Default value</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td><code>ClassLoaderLeakPreventor.stopThreads</code></td>
 *     <td><code>true</code></td>
 *     <td>Should threads tied to the web app classloader be forced to stop at application shutdown?</td>
 *   </tr>
 *   <tr>
 *     <td><code>ClassLoaderLeakPreventor.stopTimerThreads</code></td>
 *     <td><code>true</code></td>
 *     <td>Should Timer threads tied to the web app classloader be forced to stop at application shutdown?</td>
 *   </tr>
 *   <tr>
 *     <td><code>ClassLoaderLeakPreventor.executeShutdownHooks</td>
 *     <td><code>true</code></td>
 *     <td>Should shutdown hooks registered from the application be executed at application shutdown?</td>
 *   </tr>
 *   <tr>
 *     <td><code>ClassLoaderLeakPreventor.threadWaitMs</td>
 *     <td><code>5000</code> (5 seconds)</td>
 *     <td>No of milliseconds to wait for threads to finish execution, before stopping them.</td>
 *   </tr>
 *   <tr>
 *     <td><code>ClassLoaderLeakPreventor.shutdownHookWaitMs</code></td>
 *     <td><code>10000</code> (10 seconds)</td>
 *     <td>
 *       No of milliseconds to wait for shutdown hooks to finish execution, before stopping them.
 *       If set to -1 there will be no waiting at all, but Thread is allowed to run until finished.
 *     </td>
 *   </tr>
 * </table>
 * 
 * 
 * <h1>License</h1>
 * <p>This code is licensed under the <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache 2</a> license,
 * which allows you to include modified versions of the code in your distributed software, without having to release
 * your source code.</p>
 *
 * <h1>More info</h1> 
 * <p>For more info, see 
 * <a href="http://java.jiderhamn.se/2012/03/04/classloader-leaks-vi-this-means-war-leak-prevention-library/">here</a>.
 * </p>
 * 
 * <h1>Design goals</h1>
 * <p>If you want to help improve this component, you should be aware of the design goals</p>
 * <p>
 *   Primary design goal: Zero dependencies. The component should build and run using nothing but the JDK and the 
 *   Servlet API. Specifically we should <b>not</b> depend on any logging framework, since they are part of the problem.
 *   We also don't want to use any utility libraries, in order not to impose any further dependencies into any project
 *   that just wants to get rid of classloader leaks.
 *   Access to anything outside of the standard JDK (in order to prevent a known leak) should be managed
 *   with reflection.
 * </p>
 * <p>
 *   Secondary design goal: Keep the runtime component in a single <code>.java</code> file. It should be possible to
 *   just add this one <code>.java</code> file into your own source tree.
 * </p>
 * 
 * @author Mattias Jiderhamn, 2012-2013
 */
@SuppressWarnings("WeakerAccess")
public class ClassLoaderLeakPreventorListener implements ServletContextListener {
  
  /** Default no of milliseconds to wait for threads to finish execution */
  public static final int THREAD_WAIT_MS_DEFAULT = 5 * 1000; // 5 seconds

  public static final String JURT_ASYNCHRONOUS_FINALIZER = "com.sun.star.lib.util.AsynchronousFinalizer";
  
  /** Class name for per thread transaction in Caucho Resin transaction manager */
  public static final String CAUCHO_TRANSACTION_IMPL = "com.caucho.transaction.TransactionImpl";

  ///////////
  // Settings
  
  
  /** Should threads tied to the web app classloader be forced to stop at application shutdown? */
  protected boolean stopThreads = true;
  
  /** Should Timer threads tied to the web app classloader be forced to stop at application shutdown? */
  protected boolean stopTimerThreads = true;
  
  /** Should shutdown hooks registered from the application be executed at application shutdown? */
  protected boolean executeShutdownHooks = true;

  /** 
   * Should the {@code oracle.jdbc.driver.OracleTimeoutPollingThread} thread be forced to start with system classloader,
   * in case Oracle JDBC driver is present? This is normally a good idea, but can be disabled in case the Oracle JDBC
   * driver is not used even though it is on the classpath.
   */
  protected boolean startOracleTimeoutThread = true;

  /** 
   * No of milliseconds to wait for threads to finish execution, before stopping them.
   */
  protected int threadWaitMs = THREAD_WAIT_MS_DEFAULT;

  /** 
   * No of milliseconds to wait for shutdown hooks to finish execution, before stopping them.
   * If set to -1 there will be no waiting at all, but Thread is allowed to run until finished.
   */
  protected int shutdownHookWaitMs = SHUTDOWN_HOOK_WAIT_MS_DEFAULT;

  protected final Field java_lang_Thread_threadLocals;

  protected final Field java_lang_Thread_inheritableThreadLocals;

  protected final Field java_lang_ThreadLocal$ThreadLocalMap_table;

  protected Field java_lang_ThreadLocal$ThreadLocalMap$Entry_value;

  @Deprecated // TODO REMOVE
  private final Field java_security_AccessControlContext$combiner;
  
  protected ClassLoaderLeakPreventor classLoaderLeakPreventor;

  /** Other {@link javax.servlet.ServletContextListener}s to use also */
  protected final List<ServletContextListener> otherListeners = new LinkedList<ServletContextListener>();

  /** Allow setting this in test cases */
  void setStopThreads(boolean stopThreads) {
    this.stopThreads = stopThreads;
  }

  public ClassLoaderLeakPreventorListener() {
    // Initialize some reflection variables
    java_lang_Thread_threadLocals = findField(Thread.class, "threadLocals");
    java_lang_Thread_inheritableThreadLocals = findField(Thread.class, "inheritableThreadLocals");
    java_lang_ThreadLocal$ThreadLocalMap_table = findFieldOfClass("java.lang.ThreadLocal$ThreadLocalMap", "table");
    java_security_AccessControlContext$combiner = findField(AccessControlContext.class, "combiner");
    
    if(java_lang_Thread_threadLocals == null)
      error("java.lang.Thread.threadLocals not found; something is seriously wrong!");
    
    if(java_lang_Thread_inheritableThreadLocals == null)
      error("java.lang.Thread.inheritableThreadLocals not found; something is seriously wrong!");

    if(java_lang_ThreadLocal$ThreadLocalMap_table == null)
      error("java.lang.ThreadLocal$ThreadLocalMap.table not found; something is seriously wrong!");

    try{
      Class<ServletContextListener> introspectorCleanupListenerClass = 
          (Class<ServletContextListener>) Class.forName("org.springframework.web.util.IntrospectorCleanupListener");
      otherListeners.add(introspectorCleanupListenerClass.newInstance());
    }
    catch (ClassNotFoundException e) {
      // Ignore silently - Spring not present on classpath
    }
    catch(Exception e){
      error(e);
    }

    final ClassLoaderLeakPreventorFactory classLoaderLeakPreventorFactory = new ClassLoaderLeakPreventorFactory();
    
    // TODO https://github.com/mjiderhamn/classloader-leak-prevention/issues/44 Move to factory
    // This part is heavily inspired by Tomcats JreMemoryLeakPreventionListener  
    // See http://svn.apache.org/viewvc/tomcat/trunk/java/org/apache/catalina/core/JreMemoryLeakPreventionListener.java?view=markup
    classLoaderLeakPreventorFactory.addPreInitiator(new AwtToolkitInitiator());
    // initSecurityProviders()
    classLoaderLeakPreventorFactory.addPreInitiator(new JdbcDriversInitiator());
    classLoaderLeakPreventorFactory.addPreInitiator(new SunAwtAppContextInitiator());
    classLoaderLeakPreventorFactory.addPreInitiator(new SecurityPolicyInitiator());
    classLoaderLeakPreventorFactory.addPreInitiator(new SecurityProvidersInitiator());
    classLoaderLeakPreventorFactory.addPreInitiator(new DocumentBuilderFactoryInitiator());
    classLoaderLeakPreventorFactory.addPreInitiator(new ReplaceDOMNormalizerSerializerAbortException());
    classLoaderLeakPreventorFactory.addPreInitiator(new DatatypeConverterImplInitiator());
    classLoaderLeakPreventorFactory.addPreInitiator(new JavaxSecurityLoginConfigurationInitiator());
    classLoaderLeakPreventorFactory.addPreInitiator(new JarUrlConnectionInitiator());
    // Load Sun specific classes that may cause leaks
    classLoaderLeakPreventorFactory.addPreInitiator(new LdapPoolManagerInitiator());
    classLoaderLeakPreventorFactory.addPreInitiator(new Java2dDisposerInitiator());
    classLoaderLeakPreventorFactory.addPreInitiator(new SunGCInitiator());
    if(startOracleTimeoutThread)
      classLoaderLeakPreventorFactory.addPreInitiator(new OracleJdbcThreadInitiator());

    classLoaderLeakPreventorFactory.addCleanUp(new BeanIntrospectorCleanUp());
    
    // Apache Commons Pool can leave unfinished threads. Anything specific we can do?
    classLoaderLeakPreventorFactory.addCleanUp(new BeanELResolverCleanUp());
    classLoaderLeakPreventorFactory.addCleanUp(new BeanValidationCleanUp());
    classLoaderLeakPreventorFactory.addCleanUp(new JavaServerFaces2746CleanUp());
    classLoaderLeakPreventorFactory.addCleanUp(new GeoToolsCleanUp());
    // Can we do anything about Google Guice ?
    // Can we do anything about Groovy http://jira.codehaus.org/browse/GROOVY-4154 ?
    classLoaderLeakPreventorFactory.addCleanUp(new IntrospectionUtilsCleanUp());
    // Can we do anything about Logback http://jira.qos.ch/browse/LBCORE-205 ?
    classLoaderLeakPreventorFactory.addCleanUp(new IIOServiceProviderCleanUp()); // clear ImageIO registry
    classLoaderLeakPreventorFactory.addCleanUp(new MultiThreadedHttpConnectionManagerCleanUp());
    
    ////////////////////
    // Fix generic leaks
    classLoaderLeakPreventorFactory.addCleanUp(new DriverManagerCleanUp());
    
    classLoaderLeakPreventorFactory.addCleanUp(new DefaultAuthenticatorCleanUp());

    classLoaderLeakPreventorFactory.addCleanUp(new MBeanCleanUp());
    classLoaderLeakPreventorFactory.addCleanUp(new MXBeanNotificationListenersCleanUp());
    
    classLoaderLeakPreventorFactory.addCleanUp(new ShutdownHookCleanUp(executeShutdownHooks, shutdownHookWaitMs));
    classLoaderLeakPreventorFactory.addCleanUp(new PropertyEditorCleanUp());
    classLoaderLeakPreventorFactory.addCleanUp(new SecurityProviderCleanUp());
    classLoaderLeakPreventorFactory.addCleanUp(new ProxySelectorCleanUp());
    classLoaderLeakPreventorFactory.addCleanUp(new RmiTargetsCleanUp());

    classLoaderLeakPreventor = classLoaderLeakPreventorFactory
        .newLeakPreventor(ClassLoaderLeakPreventorListener.class.getClassLoader());
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Implement javax.servlet.ServletContextListener 
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  @Override
  public void contextInitialized(ServletContextEvent servletContextEvent) {
    final ServletContext servletContext = servletContextEvent.getServletContext();
    stopThreads = ! "false".equals(servletContext.getInitParameter("ClassLoaderLeakPreventor.stopThreads"));
    stopTimerThreads = ! "false".equals(servletContext.getInitParameter("ClassLoaderLeakPreventor.stopTimerThreads"));
    executeShutdownHooks = ! "false".equals(servletContext.getInitParameter("ClassLoaderLeakPreventor.executeShutdownHooks"));
    startOracleTimeoutThread = ! "false".equals(servletContext.getInitParameter("ClassLoaderLeakPreventor.startOracleTimeoutThread"));
    threadWaitMs = getIntInitParameter(servletContext, "ClassLoaderLeakPreventor.threadWaitMs", THREAD_WAIT_MS_DEFAULT);
    shutdownHookWaitMs = getIntInitParameter(servletContext, "ClassLoaderLeakPreventor.shutdownHookWaitMs", SHUTDOWN_HOOK_WAIT_MS_DEFAULT);
    
    info("Settings for " + this.getClass().getName() + " (CL: 0x" +
         Integer.toHexString(System.identityHashCode(getWebApplicationClassLoader())) + "):");
    info("  stopThreads = " + stopThreads);
    info("  stopTimerThreads = " + stopTimerThreads);
    info("  executeShutdownHooks = " + executeShutdownHooks);
    info("  threadWaitMs = " + threadWaitMs + " ms");
    info("  shutdownHookWaitMs = " + shutdownHookWaitMs + " ms");
    
    info("Initializing context by loading some known offenders with system classloader");

    classLoaderLeakPreventor.runPreClassLoaderInitiators();

    for(ServletContextListener listener : otherListeners) {
      try {
        listener.contextInitialized(servletContextEvent);
      }
      catch(Exception e){
        error(e);
      }
    }
  }

  @Override
  public void contextDestroyed(ServletContextEvent servletContextEvent) {

    final boolean jvmIsShuttingDown = isJvmShuttingDown();
    if(jvmIsShuttingDown) {
      info("JVM is shutting down - skip cleanup");
      return; // Don't do anything more
    }

    info(getClass().getName() + " shutting down context by removing known leaks (CL: 0x" + 
         Integer.toHexString(System.identityHashCode(getWebApplicationClassLoader())) + ")");

    for(ServletContextListener listener : otherListeners) {
      try {
        listener.contextDestroyed(servletContextEvent);
      }
      catch(Exception e) {
        error(e);
      }
    }
    
    classLoaderLeakPreventor.runCleanUps();

    //////////////////
    // Fix known leaks
    //////////////////
    
    // Force the execution of the cleanup code for JURT; see https://issues.apache.org/ooo/show_bug.cgi?id=122517
    forceStartOpenOfficeJurtCleanup(); // (Do this before stopThreads())
    
    ////////////////////
    // Fix generic leaks
    
    stopThreads();
    
    destroyThreadGroups();
    
    // This must be done after threads have been stopped, or new ThreadLocals may be added by those threads
    clearThreadLocalsOfAllThreads();

    unsetCachedKeepAliveTimer();
    
    try {
      try { // First try Java 1.6 method
        final Method clearCache16 = ResourceBundle.class.getMethod("clearCache", ClassLoader.class);
        debug("Since Java 1.6+ is used, we can call " + clearCache16);
        clearCache16.invoke(null, getWebApplicationClassLoader());
      }
      catch (NoSuchMethodException e) {
        // Not Java 1.6+, we have to clear manually
        final Map<?,?> cacheList = getStaticFieldValue(ResourceBundle.class, "cacheList"); // Java 5: SoftCache extends AbstractMap
        final Iterator<?> iter = cacheList.keySet().iterator();
        Field loaderRefField = null;
        while(iter.hasNext()) {
          Object key = iter.next(); // CacheKey
          
          if(loaderRefField == null) { // First time
            loaderRefField = key.getClass().getDeclaredField("loaderRef");
            loaderRefField.setAccessible(true);
          }
          WeakReference<ClassLoader> loaderRef = (WeakReference<ClassLoader>) loaderRefField.get(key); // LoaderReference extends WeakReference
          ClassLoader classLoader = loaderRef.get();
          
          if(isWebAppClassLoaderOrChild(classLoader)) {
            info("Removing ResourceBundle from cache: " + key);
            iter.remove();
          }
          
        }
      }
    }
    catch(Exception ex) {
      error(ex);
    }
    
    // (CacheKey of java.util.ResourceBundle.NONEXISTENT_BUNDLE will point to first referring classloader...)
    
    // Release this classloader from Apache Commons Logging (ACL) by calling
    //   LogFactory.release(getCurrentClassLoader());
    // Use reflection in case ACL is not present.
    // Do this last, in case other shutdown procedures want to log something.
    
    final Class<?> logFactory = findClass("org.apache.commons.logging.LogFactory");
    if(logFactory != null) { // Apache Commons Logging present
      info("Releasing web app classloader from Apache Commons Logging");
      try {
        logFactory.getMethod("release", java.lang.ClassLoader.class)
            .invoke(null, getWebApplicationClassLoader());
      }
      catch (Exception ex) {
        error(ex);
      }
    }
    
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Fix generic leaks
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  
  protected void clearThreadLocalsOfAllThreads() {
    final ThreadLocalProcessor clearingThreadLocalProcessor = getThreadLocalProcessor();
    for(Thread thread : classLoaderLeakPreventor.getAllThreads()) {
      forEachThreadLocalInThread(thread, clearingThreadLocalProcessor);
    }
  }
  
  /** Get {@link ThreadLocalProcessor} to be used. Override to customize {@link ThreadLocal} processing. */
  protected ThreadLocalProcessor getThreadLocalProcessor() {
    return new ClearingThreadLocalProcessor();
  }

  /**
   * Partially inspired by org.apache.catalina.loader.WebappClassLoader.clearReferencesThreads()
   */
  @SuppressWarnings("deprecation")
  protected void stopThreads() {
    final Class<?> workerClass = findClass("java.util.concurrent.ThreadPoolExecutor$Worker");
    final Field oracleTarget = findField(Thread.class, "target"); // Sun/Oracle JRE
    final Field ibmRunnable = findField(Thread.class, "runnable"); // IBM JRE
    final Field inheritedAccessControlContext = findField(Thread.class, "inheritedAccessControlContext");

    final boolean waitForThreads = threadWaitMs > 0;
    for(Thread thread : classLoaderLeakPreventor.getAllThreads()) {
      final Runnable runnable = (oracleTarget != null) ? 
          (Runnable) getFieldValue(oracleTarget, thread) : // Sun/Oracle JRE  
          (Runnable) getFieldValue(ibmRunnable, thread);   // IBM JRE

      final boolean runnableLoadedInWebApplication = isLoadedInWebApplication(runnable);
      if(thread != Thread.currentThread() && // Ignore current thread
         (isThreadInWebApplication(thread) || runnableLoadedInWebApplication)) {

        if (thread.getClass().getName().startsWith(JURT_ASYNCHRONOUS_FINALIZER)) {
          // Note, the thread group of this thread may be "system" if it is triggered by the Garbage Collector
          // however if triggered by us in forceStartOpenOfficeJurtCleanup() it may depend on the application server
          if(stopThreads) {
            info("Found JURT thread " + thread.getName() + "; starting " + JURTKiller.class.getSimpleName());
            new JURTKiller(thread).start();
          }
          else
            warn("JURT thread " + thread.getName() + " is still running in web app");
        }
        else if(thread.getThreadGroup() != null && 
           ("system".equals(thread.getThreadGroup().getName()) ||  // System thread
            "RMI Runtime".equals(thread.getThreadGroup().getName()))) { // RMI thread (honestly, just copied from Tomcat)
          
          if("Keep-Alive-Timer".equals(thread.getName())) {
            thread.setContextClassLoader(getWebApplicationClassLoader().getParent());
            debug("Changed contextClassLoader of HTTP keep alive thread");
          }
        }
        else if(thread.isAlive()) { // Non-system, running in web app
        
          if("java.util.TimerThread".equals(thread.getClass().getName())) {
            if(stopTimerThreads) {
              warn("Stopping Timer thread '" + thread.getName() + "' running in classloader.");
              stopTimerThread(thread);
            }
            else {
              info("Timer thread is running in classloader, but will not be stopped");
            }
          }
          else {
            // If threads is running an java.util.concurrent.ThreadPoolExecutor.Worker try shutting down the executor
            if(workerClass != null && workerClass.isInstance(runnable)) {
              if(stopThreads) {
                warn("Shutting down " + ThreadPoolExecutor.class.getName() + " running within the classloader.");
                try {
                  // java.util.concurrent.ThreadPoolExecutor, introduced in Java 1.5
                  final Field workerExecutor = findField(workerClass, "this$0");
                  final ThreadPoolExecutor executor = getFieldValue(workerExecutor, runnable);
                  executor.shutdownNow();
                }
                catch (Exception ex) {
                  error(ex);
                }
              }
              else 
                info(ThreadPoolExecutor.class.getName() + " running within the classloader will not be shut down.");
            }

            final String displayString = "'" + thread + "' of type " + thread.getClass().getName();

            if(! isLoadedInWebApplication(thread) && ! runnableLoadedInWebApplication) { // Not loaded in web app - just running there
              // This would for example be the case with org.apache.tomcat.util.threads.TaskThread
              if(waitForThreads) {
                warn("Thread " + displayString + " running in web app; waiting " + threadWaitMs);
                waitForThread(thread, threadWaitMs);
              }
              
              if(thread.isAlive() && isWebAppClassLoaderOrChild(thread.getContextClassLoader())) {
                warn("Thread " + displayString + (waitForThreads ? " still" : "") + 
                    " running in web app; changing context classloader to system classloader");
                thread.setContextClassLoader(ClassLoader.getSystemClassLoader());
              }
            }
            else if(stopThreads) { // Loaded by web app
              final String waitString = waitForThreads ? "after " + threadWaitMs + " ms " : "";
              warn("Stopping Thread " + displayString + " running in web app " + waitString);

              waitForThread(thread, threadWaitMs);

              // Normally threads should not be stopped (method is deprecated), since it may cause an inconsistent state.
              // In this case however, the alternative is a classloader leak, which may or may not be considered worse.
              if(thread.isAlive())
                thread.stop();
            }
            else {
              warn("Thread " + displayString + " is still running in web app");
            }
              
          }
        }
      }
      else { // Thread not running in web app - may have been started in contextInitialized() and need fixed ACC
        if(inheritedAccessControlContext != null && java_security_AccessControlContext$combiner != null) {
          final AccessControlContext accessControlContext = getFieldValue(inheritedAccessControlContext, thread);
          classLoaderLeakPreventor.removeDomainCombiner(thread, accessControlContext);
        }
      }
    }
  }

  protected void stopTimerThread(Thread thread) {
    // Seems it is not possible to access Timer of TimerThread, so we need to mimic Timer.cancel()
    /** 
    try {
      Timer timer = (Timer) findField(thread.getClass(), "this$0").get(thread); // This does not work!
      warn("Cancelling Timer " + timer + " / TimeThread '" + thread + "'");
      timer.cancel();
    }
    catch (IllegalAccessException iaex) {
      error(iaex);
    }
    */

    try {
      final Field newTasksMayBeScheduled = findField(thread.getClass(), "newTasksMayBeScheduled");
      final Object queue = findField(thread.getClass(), "queue").get(thread); // java.lang.TaskQueue
      final Method clear = findMethod(queue.getClass(), "clear");
      
      // Do what java.util.Timer.cancel() does
      //noinspection SynchronizationOnLocalVariableOrMethodParameter
      synchronized (queue) {
        newTasksMayBeScheduled.set(thread, Boolean.FALSE);
        clear.invoke(queue);
        queue.notify(); // "In case queue was already empty."
      }
      
      // We shouldn't need to join() here, thread will finish soon enough
    }
    catch (Exception ex) {
      error(ex);
    }
  }
  
  /**
   * Make the provided Thread stop sleep(), wait() or join() and then give it the provided no of milliseconds to finish
   * executing. 
   * @param thread The thread to wake up and wait for
   * @param waitMs The no of milliseconds to wait. If <= 0 this method does nothing.
   */
  protected void waitForThread(Thread thread, long waitMs) {
    if(waitMs > 0) {
      try {
        thread.interrupt(); // Make Thread stop waiting in sleep(), wait() or join()
      }
      catch (SecurityException e) {
        error(e);
      }

      try {
        thread.join(waitMs); // Wait for thread to run
      }
      catch (InterruptedException e) {
        // Do nothing
      }
    }
  }

  /** Destroy any ThreadGroups that are loaded by the application classloader */
  public void destroyThreadGroups() {
    try {
      ThreadGroup systemThreadGroup = Thread.currentThread().getThreadGroup();
      while(systemThreadGroup.getParent() != null) {
        systemThreadGroup = systemThreadGroup.getParent();
      }
      // systemThreadGroup should now be the topmost ThreadGroup, "system"

      int enumeratedGroups;
      ThreadGroup[] allThreadGroups;
      int noOfGroups = systemThreadGroup.activeGroupCount(); // Estimate no of groups
      do {
        noOfGroups += 10; // Make room for 10 extra
        allThreadGroups = new ThreadGroup[noOfGroups];
        enumeratedGroups = systemThreadGroup.enumerate(allThreadGroups);
      } while(enumeratedGroups >= noOfGroups); // If there was not room for all groups, try again
      
      for(ThreadGroup threadGroup : allThreadGroups) {
        if(isLoadedInWebApplication(threadGroup) && ! threadGroup.isDestroyed()) {
          warn("ThreadGroup '" + threadGroup + "' was loaded inside application, needs to be destroyed");
          
          int noOfThreads = threadGroup.activeCount();
          if(noOfThreads > 0) {
            warn("There seems to be " + noOfThreads + " running in ThreadGroup '" + threadGroup + "'; interrupting");
            try {
              threadGroup.interrupt();
            }
            catch (Exception e) {
              error(e);
            }
          }

          try {
            threadGroup.destroy();
            info("ThreadGroup '" + threadGroup + "' successfully destroyed");
          }
          catch (Exception e) {
            error(e);
          }
        }
      }
    }
    catch (Exception ex) {
      error(ex);
    }
  }
  
  /** 
   * Since Keep-Alive-Timer thread may have terminated, but still be referenced, we need to make sure it does not
   * reference this classloader.
   */
  protected void unsetCachedKeepAliveTimer() {
    Object keepAliveCache = getStaticFieldValue("sun.net.www.http.HttpClient", "kac", true);
    if(keepAliveCache != null) {
      final Thread keepAliveTimer = getFieldValue(keepAliveCache, "keepAliveTimer");
      if(keepAliveTimer != null) {
        if(isWebAppClassLoaderOrChild(keepAliveTimer.getContextClassLoader())) {
          keepAliveTimer.setContextClassLoader(getWebApplicationClassLoader().getParent());
          error("ContextClassLoader of sun.net.www.http.HttpClient cached Keep-Alive-Timer set to parent instead");
        }
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Fix specific leaks
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  /** 
   * The bug detailed at https://issues.apache.org/ooo/show_bug.cgi?id=122517 is quite tricky. This is a try to 
   * avoid the issues by force starting the threads and it's job queue.
   */
  protected void forceStartOpenOfficeJurtCleanup() {
    if(stopThreads) {
      if(isLoadedByWebApplication(findClass(JURT_ASYNCHRONOUS_FINALIZER))) {
        /* 
          The com.sun.star.lib.util.AsynchronousFinalizer class was found and loaded, which means that in case the
          static block that starts the daemon thread had not been started yet, it has been started now.
          
          Now let's force Garbage Collection, with the hopes of having the finalize()ers that put Jobs on the
          AsynchronousFinalizer queue be executed. Then just leave it, and handle the rest in {@link #stopThreads}.
          */
        info("OpenOffice JURT AsynchronousFinalizer thread started - forcing garbage collection to invoke finalizers");
        gc();
      }
    }
    else {
      // Check for class existence without loading class and thus executing static block
      if(getWebApplicationClassLoader().getResource("com/sun/star/lib/util/AsynchronousFinalizer.class") != null) {
        warn("OpenOffice JURT AsynchronousFinalizer thread will not be stopped if started, as stopThreads is false");
        /* 
         By forcing Garbage Collection, we'll hopefully start the thread now, in case it would have been started by
         GC later, so that at least it will appear in the logs. 
         */
        gc();
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Utility methods
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  
  protected ClassLoader getWebApplicationClassLoader() {
    return ClassLoaderLeakPreventorListener.class.getClassLoader();
    // Alternative return Thread.currentThread().getContextClassLoader();
  }
  
  /** Test if provided object is loaded with web application classloader */
  protected boolean isLoadedInWebApplication(Object o) {
    return (o instanceof Class) && isLoadedByWebApplication((Class<?>)o) || // Object is a java.lang.Class instance 
        o != null && isLoadedByWebApplication(o.getClass());
  }

  /** Test if provided class is loaded with web application classloader */
  protected boolean isLoadedByWebApplication(Class<?> clazz) {
    return clazz != null && isWebAppClassLoaderOrChild(clazz.getClassLoader());
  }

  /** Test if provided ClassLoader is the classloader of the web application, or a child thereof */
  protected boolean isWebAppClassLoaderOrChild(ClassLoader cl) {
    final ClassLoader webAppCL = getWebApplicationClassLoader();
    // final ClassLoader webAppCL = Thread.currentThread().getContextClassLoader();

    while(cl != null) {
      if(cl == webAppCL)
        return true;
      
      cl = cl.getParent();
    }

    return false;
  }

  protected boolean isThreadInWebApplication(Thread thread) {
    return isLoadedInWebApplication(thread) || // Custom Thread class in web app
       isWebAppClassLoaderOrChild(thread.getContextClassLoader()); // Running in web application
  }
  
  protected <E> E getStaticFieldValue(Class<?> clazz, String fieldName) {
    Field staticField = findField(clazz, fieldName);
    return (staticField != null) ? (E) getStaticFieldValue(staticField) : null;
  }

  protected <E> E getStaticFieldValue(String className, String fieldName) {
    return (E) getStaticFieldValue(className, fieldName, false);
  }
  
  protected <E> E getStaticFieldValue(String className, String fieldName, boolean trySystemCL) {
    Field staticField = findFieldOfClass(className, fieldName, trySystemCL);
    return (staticField != null) ? (E) getStaticFieldValue(staticField) : null;
  }
  
  protected Field findFieldOfClass(String className, String fieldName) {
    return findFieldOfClass(className, fieldName, false);
  }
  
  protected Field findFieldOfClass(String className, String fieldName, boolean trySystemCL) {
    Class<?> clazz = findClass(className, trySystemCL);
    if(clazz != null) {
      return findField(clazz, fieldName);
    }
    else
      return null;
  }
  
  protected Class<?> findClass(String className) {
    return findClass(className, false);
  }
  
  protected Class<?> findClass(String className, boolean trySystemCL) {
    return classLoaderLeakPreventor.findClass(className, trySystemCL);
  }
  
  protected Field findField(Class<?> clazz, String fieldName) {
    return classLoaderLeakPreventor.findField(clazz, fieldName);
  }
  
  protected <T> T getStaticFieldValue(Field field) {
    return classLoaderLeakPreventor.getStaticFieldValue(field);
  }
  
  protected <T> T getFieldValue(Object obj, String fieldName) {
    final Field field = findField(obj.getClass(), fieldName);
    return (T) getFieldValue(field, obj);
  }
  
  protected <T> T getFieldValue(Field field, Object obj) {
    try {
      return (T) field.get(obj);
    }
    catch (Exception ex) {
      warn(ex);
      // Silently ignore
      return null;
    }
  }

  protected Method findMethod(Class<?> clazz, String methodName, Class... parameterTypes) {
    return classLoaderLeakPreventor.findMethod(clazz, methodName, parameterTypes);
  }
  
  /** Is the JVM currently shutting down? */
  protected boolean isJvmShuttingDown() {
    try {
      final Thread dummy = new Thread(); // Will never be started
      Runtime.getRuntime().removeShutdownHook(dummy);
      return false;
    }
    catch (IllegalStateException isex) {
      return true; // Shutting down
    }
    catch (Throwable t) { // Any other Exception, assume we are not shutting down
      return false;
    }
  }

  /**
   * Loop ThreadLocals and inheritable ThreadLocals in current Thread
   * and for each found, invoke the callback interface
   */
  protected void forEachThreadLocalInCurrentThread(ThreadLocalProcessor threadLocalProcessor) {
    final Thread thread = Thread.currentThread();

    forEachThreadLocalInThread(thread, threadLocalProcessor);
  }

  protected void forEachThreadLocalInThread(Thread thread, ThreadLocalProcessor threadLocalProcessor) {
    try {
      if(java_lang_Thread_threadLocals != null) {
        processThreadLocalMap(thread, threadLocalProcessor, java_lang_Thread_threadLocals.get(thread));
      }

      if(java_lang_Thread_inheritableThreadLocals != null) {
        processThreadLocalMap(thread, threadLocalProcessor, java_lang_Thread_inheritableThreadLocals.get(thread));
      }
    }
    catch (/*IllegalAccess*/Exception ex) {
      error(ex);
    }
  }

  protected void processThreadLocalMap(Thread thread, ThreadLocalProcessor threadLocalProcessor, Object threadLocalMap) throws IllegalAccessException {
    if(threadLocalMap != null && java_lang_ThreadLocal$ThreadLocalMap_table != null) {
      Field resin_suspendState = null;
      Field resin_isSuspended = null;
      final Object[] threadLocalMapTable = (Object[]) java_lang_ThreadLocal$ThreadLocalMap_table.get(threadLocalMap); // java.lang.ThreadLocal.ThreadLocalMap.Entry[]
      for(Object entry : threadLocalMapTable) {
        if(entry != null) {
          // Key is kept in WeakReference
          Reference<?> reference = (Reference<?>) entry;
          final ThreadLocal<?> threadLocal = (ThreadLocal<?>) reference.get();

          if(java_lang_ThreadLocal$ThreadLocalMap$Entry_value == null) {
            java_lang_ThreadLocal$ThreadLocalMap$Entry_value = findField(entry.getClass(), "value");
          }
          
          final Object value = java_lang_ThreadLocal$ThreadLocalMap$Entry_value.get(entry);
          
          // Workaround for http://bugs.caucho.com/view.php?id=5647
          if(value != null && CAUCHO_TRANSACTION_IMPL.equals(value.getClass().getName())) { // Resin transaction
            if(resin_suspendState == null && resin_isSuspended == null) { // First thread with Resin transaction, look up fields
              resin_suspendState = findField(value.getClass(), "_suspendState");
              resin_isSuspended = findField(value.getClass(), "_isSuspended");
            }

            if(resin_suspendState != null && resin_isSuspended != null) { // Both fields exist (as per version 4.0.37)
              if(getFieldValue(resin_suspendState, value) != null) { // There is a suspended state that may cause leaks
                // In theory a new transaction can be started and suspended between where we read and write the state,
                // and flag, therefore we suspend the thread meanwhile.
                try {
                  //noinspection deprecation
                  thread.suspend(); // Suspend the thread
                  if(getFieldValue(resin_suspendState, value) != null) { // Re-read suspend state when thread is suspended
                    final Object isSuspended = getFieldValue(resin_isSuspended, value);
                    if(! (isSuspended instanceof Boolean)) {
                      error(thread.toString() + " has " + CAUCHO_TRANSACTION_IMPL + " but _isSuspended is not boolean: " + isSuspended);
                    }
                    else if((Boolean)isSuspended) { // Is currently suspended - suspend state is correct
                      debug(thread.toString() + " has " + CAUCHO_TRANSACTION_IMPL + " that is suspended");
                    }
                    else { // Is not suspended, and thus should not have suspend state
                      resin_suspendState.set(value, null);
                      error(thread.toString() + " had " + CAUCHO_TRANSACTION_IMPL + " with unused _suspendState that was removed");
                    }
                  }
                }
                catch (Throwable t) { // Such as SecurityException
                  error(t);
                }
                finally {
                  //noinspection deprecation
                  thread.resume();
                }
              }
            }
          }

          threadLocalProcessor.process(thread, reference, threadLocal, value);
        }
      }
    }
  }

  protected interface ThreadLocalProcessor {
    void process(Thread thread, Reference<?> entry, ThreadLocal<?> threadLocal, Object value);
  }

  /** ThreadLocalProcessor that detects and warns about potential leaks */
  protected class WarningThreadLocalProcessor implements ThreadLocalProcessor {
    @Override
    public final void process(Thread thread, Reference<?> entry, ThreadLocal<?> threadLocal, Object value) {
      final boolean customThreadLocal = isLoadedInWebApplication(threadLocal); // This is not an actual problem
      final boolean valueLoadedInWebApp = isLoadedInWebApplication(value);
      if(customThreadLocal || valueLoadedInWebApp ||
         (value instanceof ClassLoader && isWebAppClassLoaderOrChild((ClassLoader) value))) { // The value is classloader (child) itself
        // This ThreadLocal is either itself loaded by the web app classloader, or it's value is
        // Let's do something about it
        
        StringBuilder message = new StringBuilder();
        if(threadLocal != null) {
          if(customThreadLocal) {
            message.append("Custom ");
          }
          message.append("ThreadLocal of type ").append(threadLocal.getClass().getName()).append(": ").append(threadLocal);
        }
        else {
          message.append("Unknown ThreadLocal");
        }
        message.append(" with value ").append(value);
        if(value != null) {
          message.append(" of type ").append(value.getClass().getName());
          if(valueLoadedInWebApp)
            message.append(" that is loaded by web app");
        }

        
        // Process the detected potential leak
        processLeak(thread, entry, threadLocal, value, message.toString());
      }
    }
    
    /**
     * After having detected potential ThreadLocal leak, this method is called. Default implementation only issues
     * a warning. Subclasses may override this method to perform further processing, such as clean up. 
     */
    protected void processLeak(Thread thread, Reference<?> entry, ThreadLocal<?> threadLocal, Object value, String message) {
      warn(message);
    } 
  }
  
  /** ThreadLocalProcessor that not only detects and warns about potential leaks, but also tries to clear them */
  protected class ClearingThreadLocalProcessor extends WarningThreadLocalProcessor {
    @Override
    protected void processLeak(Thread thread, Reference<?> entry, ThreadLocal<?> threadLocal, Object value, String message) {
      if(threadLocal != null && thread == Thread.currentThread()) { // If running for current thread and we have the ThreadLocal ...
        // ... remove properly
        info(message + " will be remove()d from " + thread);
        threadLocal.remove();
      }
      else { // We cannot remove entry properly, so just make it stale
        info(message + " will be made stale for later expunging from " + thread);
      }

      // It seems like remove() doesn't really do the job, so play it safe and remove references from entry either way
      // (Example problem org.infinispan.context.SingleKeyNonTxInvocationContext) 
      entry.clear(); // Clear the key

      if(java_lang_ThreadLocal$ThreadLocalMap$Entry_value == null) {
        java_lang_ThreadLocal$ThreadLocalMap$Entry_value = findField(entry.getClass(), "value");
      }

      try {
        java_lang_ThreadLocal$ThreadLocalMap$Entry_value.set(entry, null); // Clear value to avoid circular references
      }
      catch (IllegalAccessException iaex) {
        error(iaex);
      }
    }
  }

  /** Parse init parameter for integer value, returning default if not found or invalid */
  protected static int getIntInitParameter(ServletContext servletContext, String parameterName, int defaultValue) {
    final String parameterString = servletContext.getInitParameter(parameterName);
    if(parameterString != null && parameterString.trim().length() > 0) {
      try {
        return Integer.parseInt(parameterString);
      }
      catch (NumberFormatException e) {
        // Do nothing, return default value
      }
    }
    return defaultValue;
  }

  /**
   * Unlike <code>{@link System#gc()}</code> this method guarantees that garbage collection has been performed before
   * returning.
   */
  protected static void gc() {
    if (isDisableExplicitGCEnabled()) {
      System.err.println(ClassLoaderLeakPreventorListener.class.getSimpleName() + ": "
          + "Skipping GC call since -XX:+DisableExplicitGC is supplied as VM option.");
      return;
    }
    
    Object obj = new Object();
    WeakReference<Object> ref = new WeakReference<Object>(obj);
    //noinspection UnusedAssignment
    obj = null;
    while(ref.get() != null) {
      System.gc();
    }
  }
  
  /**
   * Check is "-XX:+DisableExplicitGC" enabled.
   *
   * @return true is "-XX:+DisableExplicitGC" is set als vm argument, false otherwise.
   */
  private static boolean isDisableExplicitGCEnabled() {
    RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
    List<String> aList = bean.getInputArguments();

    return aList.contains("-XX:+DisableExplicitGC");
  }  
  
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Log methods
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  
  /*
   * Since logging frameworks are part of the problem, we don't want to depend on any of them here.
   * Feel free however to subclass or fork and use a log framework, in case you think you know what you're doing.
   */
  
  protected String getLogPrefix() {
    return ClassLoaderLeakPreventorListener.class.getSimpleName() + ": ";
  }
  
  /**
   * To "turn off" debug logging override this method in a subclass and make that subclass method empty.
   */
  protected void debug(String s) {
    System.out.println(getLogPrefix() + s);
  } 

  /**
   * To "turn off" info logging override this method in a subclass and make that subclass method empty.
   */
  protected void info(String s) {
    System.out.println(getLogPrefix() + s);
  } 

  /**
   * To "turn off" warn logging override this method in a subclass and make that subclass method empty.
   * Also turn off {@link #warn(Throwable)}.
   */
  protected void warn(String s) {
    System.err.println(getLogPrefix() + s);
  } 

  /**
   * To "turn off" warn logging override this method in a subclass and make that subclass method empty.
   * Also turn off {@link #warn(String)}.
   */
  protected void warn(Throwable t) {
    t.printStackTrace(System.err);
  } 

  /**
   * To "turn off" error logging override this method in a subclass and make that subclass method empty.
   * Also turn off {@link #error(Throwable)}.
   */
  protected void error(String s) {
    System.err.println(getLogPrefix() + s);
  } 

  /**
   * To "turn off" error logging override this method in a subclass and make that subclass method empty.
   * Also turn off {@link #error(String)}.
   */
  protected void error(Throwable t) {
    t.printStackTrace(System.err);
  }
  
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  
  /** 
   * Inner class with the sole task of killing JURT finalizer thread after it is done processing jobs. 
   * We need to postpone the stopping of this thread, since more Jobs may in theory be add()ed when this web application
   * instance is closing down and being garbage collected.
   * See https://issues.apache.org/ooo/show_bug.cgi?id=122517
   */
  protected class JURTKiller extends Thread {
    
    private final Thread jurtThread;
    
    private final List<?> jurtQueue;

    public JURTKiller(Thread jurtThread) {
      super("JURTKiller");
      this.jurtThread = jurtThread;
      jurtQueue = getStaticFieldValue(JURT_ASYNCHRONOUS_FINALIZER, "queue");
    }

    @Override
    public void run() {
      if(jurtQueue == null || jurtThread == null) {
        error(getName() + ": No queue or thread!?");
        return;
      }
      if(! jurtThread.isAlive()) {
        warn(getName() + ": " + jurtThread.getName() + " is already dead?");
      }
      
      boolean queueIsEmpty = false;
      while(! queueIsEmpty) {
        try {
          debug(getName() + " goes to sleep for " + THREAD_WAIT_MS_DEFAULT + " ms");
          Thread.sleep(THREAD_WAIT_MS_DEFAULT);
        }
        catch (InterruptedException e) {
          // Do nothing
        }

        if(State.RUNNABLE != jurtThread.getState()) { // Unless thread is currently executing a Job
          debug(getName() + " about to force Garbage Collection");
          gc(); // Force garbage collection, which may put new items on queue

          synchronized (jurtQueue) {
            queueIsEmpty = jurtQueue.isEmpty();
            debug(getName() + ": JURT queue is empty? " + queueIsEmpty);
          }
        }
        else 
          debug(getName() + ": JURT thread " + jurtThread.getName() + " is executing Job");
      }
      
      info(getName() + " about to kill " + jurtThread);
      if(jurtThread.isAlive()) {
        //noinspection deprecation
        jurtThread.stop();
      }
    }
  }
}

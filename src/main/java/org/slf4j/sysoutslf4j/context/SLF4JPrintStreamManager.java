package org.slf4j.sysoutslf4j.context;

import static org.slf4j.sysoutslf4j.context.ClassLoaderUtils.getJarURL;
import static org.slf4j.sysoutslf4j.context.ClassLoaderUtils.loadClass;
import static org.slf4j.sysoutslf4j.context.ClassLoaderUtils.getSystemClassLoader;

import java.io.PrintStream;
import java.net.URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.sysoutslf4j.common.ReflectionUtils;
import org.slf4j.sysoutslf4j.common.SLF4JPrintStream;
import org.slf4j.sysoutslf4j.common.SystemOutput;
import org.slf4j.sysoutslf4j.context.exceptionhandlers.ExceptionHandlingStrategy;
import org.slf4j.sysoutslf4j.context.exceptionhandlers.ExceptionHandlingStrategyFactory;
import org.slf4j.sysoutslf4j.system.SLF4JPrintStreamImpl;
import org.slf4j.sysoutslf4j.system.SLF4JPrintStreamConfigurator;

class SLF4JPrintStreamManager {
	
	private static final String LINE_END = System.getProperty("line.separator");

	private final Logger log = LoggerFactory.getLogger(SysOutOverSLF4J.class);

	void sendSystemOutAndErrToSLF4J(final LogLevel outLevel, final LogLevel errLevel,
			final ExceptionHandlingStrategyFactory exceptionHandlingStrategyFactory) {
		makeSystemOutputsSLF4JPrintStreamsIfNecessary();
		sendSystemOutAndErrToSLF4JForThisContext(outLevel, errLevel, exceptionHandlingStrategyFactory);
		log.info("Redirected System.out and System.err to SLF4J for this context");
	}

	private void makeSystemOutputsSLF4JPrintStreamsIfNecessary() {
		if (systemOutputsAreSLF4JPrintStreams()) {
			log.debug("System.out and System.err are already SLF4JPrintStreams");
		} else {
			makeSystemOutputsSLF4JPrintStreams();
			log.info("Replaced standard System.out and System.err PrintStreams with SLF4JPrintStreams");
		}
	}

	private boolean systemOutputsAreSLF4JPrintStreams() {
		return System.out.getClass().getName().equals(SLF4JPrintStreamImpl.class.getName());
	}

	private void makeSystemOutputsSLF4JPrintStreams() {
		ReflectionUtils.invokeStaticMethod("replaceSystemOutputsWithSLF4JPrintStreams", getSlf4jPrintStreamConfiguratorClass());
	}

	private Class<?> getSlf4jPrintStreamConfiguratorClass() {
		Class<?> slf4jPrintStreamConfiguratorClass = getConfiguratorClassFromSLF4JPrintStreamClassLoader();
		if (slf4jPrintStreamConfiguratorClass == null) {
			slf4jPrintStreamConfiguratorClass = getConfiguratorClassFromSystemClassLoader();
		}
		if (slf4jPrintStreamConfiguratorClass == null) {
			slf4jPrintStreamConfiguratorClass = addConfiguratorClassToSystemClassLoaderAndGet();
		}
		if (slf4jPrintStreamConfiguratorClass == null) {
			slf4jPrintStreamConfiguratorClass = getConfiguratorClassFromCurrentClassLoader();
		}
		return slf4jPrintStreamConfiguratorClass;
	}

	private Class<?> getConfiguratorClassFromSLF4JPrintStreamClassLoader() {
		if (systemOutputsAreSLF4JPrintStreams()) {
			try {
				final ClassLoader classLoader = System.out.getClass().getClassLoader();
				return loadClass(classLoader, SLF4JPrintStreamConfigurator.class);
			} catch (SecurityException se) { /* ignore */}
		}
		return null;
	}

	private Class<?> getConfiguratorClassFromSystemClassLoader() {
		try {
			return getSystemClassLoader().loadClass(SLF4JPrintStreamConfigurator.class.getName());
		} catch (Exception e) {
			return null;
		}
	}

	private Class<?> addConfiguratorClassToSystemClassLoaderAndGet() {
		try {
			URL jarUrl = getJarURL(SLF4JPrintStreamConfigurator.class);
			ReflectionUtils.invokeMethod("addUrl", getSystemClassLoader(), URL.class, jarUrl);
			return getSystemClassLoader().loadClass(SLF4JPrintStreamConfigurator.class.getName());
		} catch (Exception e) {
			reportFailureToAvoidClassLoaderLeak(e);
			return null;
		}
	}

	private void reportFailureToAvoidClassLoaderLeak(Exception e) {
		log.warn("Unable to force syout-over-slf4j jar url into system class loader  and " +
				"then load class [" + SLF4JPrintStreamConfigurator.class + "] from the system class loader." + LINE_END +
				"Unfortunately it is not possible to set up Sysout over SLF4J on this system without introducing " +
				"a class loader memory leak." + LINE_END +
				"If you never need to discard the current class loader [" + getClass().getClassLoader() + "] this will not be " +
				"a problem and you can suppress this warning." + LINE_END +
				"If you wish to avoid a class loader memory leak you can place sysout-over-slf4j.jar on the system classpath " +
				"IN ADDITION TO (*not* instead of) the local context's classpath", e);
	}

	private Class<SLF4JPrintStreamConfigurator> getConfiguratorClassFromCurrentClassLoader() {
		return SLF4JPrintStreamConfigurator.class;
	}

	private void sendSystemOutAndErrToSLF4JForThisContext(final LogLevel outLevel, final LogLevel errLevel, 
			final ExceptionHandlingStrategyFactory exceptionHandlingStrategyFactory) {
		registerNewLoggerAppender(exceptionHandlingStrategyFactory, SLF4JPrintStreamProxy.wrap(SystemOutput.OUT.get()), outLevel);
		registerNewLoggerAppender(exceptionHandlingStrategyFactory, SLF4JPrintStreamProxy.wrap(SystemOutput.ERR.get()), errLevel);
	}

	private void registerNewLoggerAppender(
			final ExceptionHandlingStrategyFactory exceptionHandlingStrategyFactory,
			final SLF4JPrintStream slf4jPrintStream, final LogLevel logLevel) {

		final PrintStream originalPrintStream = slf4jPrintStream.getOriginalPrintStream();
		final ExceptionHandlingStrategy exceptionHandlingStrategy = 
			exceptionHandlingStrategyFactory.makeExceptionHandlingStrategy(logLevel, originalPrintStream);
		final Object loggerAppender = 
			new LoggerAppenderImpl(logLevel, exceptionHandlingStrategy, originalPrintStream);
		ReferenceHolder.preventGarbageCollectionForLifeOfClassLoader(loggerAppender);
		slf4jPrintStream.registerLoggerAppender(loggerAppender);
	}

	void stopSendingSystemOutAndErrToSLF4J() {
		try {
			for (SystemOutput systemOutput : SystemOutput.values()) {
				SLF4JPrintStream slf4jPrintStream = SLF4JPrintStreamProxy.wrap(systemOutput.get());
				slf4jPrintStream.deregisterLoggerAppender();
			}
		} catch (IllegalArgumentException iae) {
			log.warn("Cannot stop sending System.out and System.err to SLF4J - they are not being sent there at the moment");
		}
	}

	void restoreOriginalSystemOutputsIfNecessary() {
		if (systemOutputsAreSLF4JPrintStreams()) {
			restoreOriginalSystemOutputs();
			log.info("Restored original System.out and System.err");
		} else {
			log.warn("System.out and System.err are not SLF4JPrintStreams - cannot restore");
		}
	}

	private void restoreOriginalSystemOutputs() {
		ReflectionUtils.invokeStaticMethod("restoreOriginalSystemOutputs", getSlf4jPrintStreamConfiguratorClass());
	}

}
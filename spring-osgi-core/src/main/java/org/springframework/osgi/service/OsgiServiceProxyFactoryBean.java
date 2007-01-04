/*
 * Copyright 2002-2006 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Created on 23-Jan-2006 by Adrian Colyer
 */
package org.springframework.osgi.service;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.target.HotSwappableTargetSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.ConstantException;
import org.springframework.core.Constants;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.osgi.context.BundleContextAware;
import org.springframework.osgi.context.support.LocalBundleContext;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Factory bean for OSGi services. Returns a proxy implementing the service
 * interface. This allows Spring to manage service lifecycle events (such as the
 * bundle providing the service being stopped and restarted) and transparently
 * rebind to a new service instance if one is available.
 * 
 * @author Adrian Colyer
 * @author Hal Hildebrand
 * @since 2.0
 */
public class OsgiServiceProxyFactoryBean implements FactoryBean, InitializingBean, DisposableBean, BundleContextAware,
		ApplicationContextAware {

	public static final long DEFAULT_MILLIS_BETWEEN_RETRIES = 1000;

	public static final int DEFAULT_MAX_RETRIES = 3;

	public static final String FILTER_ATTRIBUTE = "filter";

	public static final String INTERFACE_ATTRIBUTE = "interface";

	public static final String CARDINALITY_ATTRIBUTE = "cardinality";

	/**
	 * Reference classloading options costants.
	 * 
	 * @author Costin Leau
	 */
	protected static class ReferenceClassLoadingOptions {
		public static final int CLIENT = 0;

		public static final int SERVICE_PROVIDER = 1;

		public static final int UNMANAGED = 2;
	}

	/**
	 * Cardinality constants.
	 * 
	 * @author Costin Leau
	 */
	protected static class Cardinality {
		public static final int C_0__1 = 0;

		public static final int C_0__N = 1;

		public static final int C_1__1 = 2;

		public static final int C_1__N = 3;
	}

	private static final Log logger = LogFactory.getLog(OsgiServiceProxyFactoryBean.class);

	private BundleContext bundleContext;

	private int retryTimes = DEFAULT_MAX_RETRIES;

	private int cardinality;

	private int contextClassloader;

	private long timeout;

	private TargetSourceLifecycleListener[] listeners = new TargetSourceLifecycleListener[0];

	private long retryDelayMs = DEFAULT_MILLIS_BETWEEN_RETRIES;

	// not required to be an interface, but usually should be...
	private Class serviceType;

	// filter used to narrow service matches, may be null
	private String filter;

	// if looking for a bean published as a service, this is the name we're
	// after
	private String beanName;

	// reference to our app context (we need the classloader for proxying...)
	private ApplicationContext applicationContext;

	private Listener listener;

	private String id;

	// Constructed object of this factory
	private Object proxy;

	private String filterStringForServiceLookup;

	private static final Constants CARDINALITY = new Constants(Cardinality.class);

	private static final Constants REFERENCE_CL_OPTIONS = new Constants(ReferenceClassLoadingOptions.class);

	public static final String OBJECTCLASS = "objectClass";

	public static int translateCardinality(String cardinality) {
		return CARDINALITY.asNumber("C_".concat(cardinality.replace('.', '_'))).intValue();
	}

	public static boolean atLeastOneRequired(int c) {
		return Cardinality.C_1__1 == c || Cardinality.C_1__N == c;
	}

	public static boolean moreThanOneExpected(int c) {
		return Cardinality.C_0__N == c || Cardinality.C_1__N == c;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	public Object getObject() throws Exception {
		return proxy;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 */
	public Class getObjectType() {
		// TODO: return a class that returns all required interfaces
		return getInterface();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.springframework.beans.factory.FactoryBean#isSingleton()
	 */
	public boolean isSingleton() {
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	public void afterPropertiesSet() throws Exception {
		Assert.notNull(this.bundleContext, "Required bundleContext property was not set");
		Assert.notNull(getInterface(), "Required serviceType property was not set");

		if (getFilter() != null) {
			// this call forces parsing of the filter string to generate an
			// exception right
			// now if it is not well-formed
			try {
				FrameworkUtil.createFilter(getFilterStringForServiceLookup());
			}
			catch (InvalidSyntaxException ex) {
				throw (IllegalArgumentException) new IllegalArgumentException("Filter string '" + getFilter()
						+ "' set on OsgiServiceProxyFactoryBean has invalid syntax: " + ex.getMessage()).initCause(ex);
			}
		}
		Assert.notNull(this.applicationContext, "Required applicationContext property was not set");

		// TODO: Costin: why is this check necessary?
		if (!(this.applicationContext instanceof DefaultResourceLoader)) {
			throw new IllegalArgumentException("ApplicationContext does not provide access to classloader, "
					+ "provided type was : '" + this.applicationContext.getClass().getName()
					+ "' which does not extend DefaultResourceLoader");
		}

		boolean serviceAvailable = constructProxy();
		bundleContext.addServiceListener(listener, getFilterStringForServiceLookup());
		if (serviceAvailable) {
			bind();
		}
	}

	protected boolean constructProxy() throws Exception {
		Object service = null;
		ServiceReference reference = null;
		final Class serviceInterface = getInterface();
		final String filter = getFilterStringForServiceLookup();
		boolean serviceAvailable = true;

		// Create the object which stands in for the actual service when the
		// service is unavailable
		InvocationHandler unavailableServiceHandler = new InvocationHandler() {
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				if (cardinality == Cardinality.C_0__1 || cardinality == Cardinality.C_0__N) {
					if (method.getReturnType().equals(Void.TYPE)) {
						return null;
					}
				}
				throw new ServiceUnavailableException("Service is currently unavailable", serviceInterface, filter);
			}
		};

		Object unavailableService = Proxy.newProxyInstance(serviceInterface.getClassLoader(),
				new Class[] { serviceInterface }, unavailableServiceHandler);

		// Lookup the initial service
		for (int i = 0; i < retryTimes; i++) {
			try {
				reference = OsgiServiceUtils.getService(this.bundleContext, getInterface(),
						getFilterStringForServiceLookup());

				if (logger.isDebugEnabled()) {
					logger.debug("Resolved service reference: [" + reference + "] after " + (i + 1) + " attempts");
				}
			}
			catch (NoSuchServiceException nsse) {
				Thread.sleep(retryDelayMs);
			}
			if (reference != null) {
				service = bundleContext.getService(reference); // obviously
																// only works
																// for
																// cardinality =
																// 1
				break;
			}
		}
		if (service == null) {
			if (atLeastOneRequired(cardinality)) {
				throw new NoSuchServiceException("The service of type '" + getInterface().getName()
						+ "' matching filter '" + getFilterStringForServiceLookup() + "' was not available.",
						getInterface(), filter);
			}
			else {
				service = unavailableService;
				serviceAvailable = false;
			}
		}

		// Our listener to handle the service lifecycle
		listener = new Listener(unavailableService);

		HotSwappableTargetSource targetSource = new HotSwappableTargetSource(service);
		listener.setTargetSource(targetSource);

		ProxyFactory pf = new ProxyFactory();
		if (getInterface().isInterface()) {
			pf.setInterfaces(new Class[] { getInterface() });
		}
		pf.setTargetSource(targetSource);
		OsgiServiceInterceptor interceptor = new OsgiServiceInterceptor(targetSource, unavailableService);

		interceptor.setMaxRetries(this.timeout != 0 ? this.retryTimes : Integer.MAX_VALUE);
		interceptor.setRetryIntervalMillis(this.retryDelayMs);

		pf.addAdvice(interceptor);

		// Context classloader support.
		switch (contextClassloader) {
		case ReferenceClassLoadingOptions.CLIENT:
			pf.addAdvice(new BundleContextClassLoaderAdvice(bundleContext.getBundle()));
			break;
		case ReferenceClassLoadingOptions.SERVICE_PROVIDER:
			pf.addAdvice(new BundleContextClassLoaderAdvice(reference.getBundle()));
			break;
		case ReferenceClassLoadingOptions.UNMANAGED:
			break;
		}

		// Add advice for pushing the bundle context
		pf.addAdvice(new LocalBundleContext(bundleContext.getBundle()));

		proxy = pf.getProxy(applicationContext.getClassLoader());
		return serviceAvailable;
	}

	/**
	 * @return Returns the serviceType.
	 */
	public Class getInterface() {
		return this.serviceType;
	}

	/**
	 * The type that the OSGi service was registered with
	 */
	public void setInterface(Class serviceType) {
		this.serviceType = serviceType;
	}

	/**
	 * The optional cardinality attribute allows a reference cardinality to be
	 * specified (0..1, 1..1, 0..n, or 1..n). The default is '1..1'.
	 * 
	 * @param cardinality
	 */
	public void setCardinality(String cardinality) {
		// transform string to the constant representation
		// a. C_ is appended to the string
		// b. . -> _

		if (cardinality != null) {
			try {
				this.cardinality = translateCardinality(cardinality);
				return;
			}
			catch (ConstantException ex) {
				// catch exception to not confuse the user with a different
				// constant name(will be handled afterwards)
			}
		}
		throw new IllegalArgumentException("invalid constant, " + cardinality);
	}

	// public void setContextClassloader(int options) {
	// if (!REFERENCE_CL_OPTIONS.getValues(null).contains(new Integer(options)))
	// throw new IllegalArgumentException("only reference classloader options
	// allowed");
	//
	// this.contextClassloader = options;
	// }

	public void setContextClassloader(String classLoaderManagementOption) {
		// transform "-" into "_" (for service-provider)
		Assert.notNull(classLoaderManagementOption, "non-null argument required");
		String option = classLoaderManagementOption.replace('-', '_');
		this.contextClassloader = REFERENCE_CL_OPTIONS.asNumber(option).intValue();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.springframework.osgi.context.BundleContextAware#setBundleContext(org.osgi.framework.BundleContext)
	 */
	public void setBundleContext(BundleContext context) {
		this.bundleContext = context;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	/**
	 * @return Returns the beanName.
	 */
	public String getBeanName() {
		return this.beanName;
	}

	/**
	 * To find a bean published as a service by the OsgiServiceExporter, simply
	 * set this property. You may specify additional filtering criteria if
	 * needed (using the filter property) but this is not required.
	 */
	public void setBeanName(String beanName) {
		this.beanName = beanName;
	}

	/**
	 * How many times should we attempt to rebind to a target service if the
	 * service we are currently using is unregistered. Default is 3 times. <p/>
	 * Changing this property after initialization is complete has no effect.
	 * 
	 * @param maxRetries The maxRetries to set.
	 */
	public void setRetryTimes(int maxRetries) {
		this.retryTimes = maxRetries;
	}

	public int getRetryTimes() {
		return retryTimes;
	}

	/**
	 * How long should we wait between failed attempts at rebinding to a service
	 * that has been unregistered. <p/> Changing this property after
	 * initialization is complete has no effect.
	 * 
	 * @param millisBetweenRetries The millisBetweenRetries to set.
	 */
	public void setRetryDelayMs(long millisBetweenRetries) {
		this.retryDelayMs = millisBetweenRetries;
	}

	public long getRetryDelayMs() {
		return retryDelayMs;
	}

	// this is as nasty as dynamic sql generation.
	// build an osgi filter string to find the service we are
	// looking for.
	private String getFilterStringForServiceLookup() {
		if (filterStringForServiceLookup != null) {
			return filterStringForServiceLookup;
		}
		StringBuffer sb = new StringBuffer();
		boolean andFilterWithInterfaceName = ((getFilter() != null) || (getBeanName() != null));
		if (andFilterWithInterfaceName) {
			sb.append("(&");
		}
		if (getFilter() != null) {
			sb.append(getFilter());
		}
		sb.append("(");
		sb.append(OBJECTCLASS);
		sb.append("=");
		sb.append(getInterface().getName());
		sb.append(")");

		if (getBeanName() != null) {
			sb.append("(");
			sb.append(BeanNameServicePropertiesResolver.BEAN_NAME_PROPERTY_KEY);
			sb.append("=");
			sb.append(getBeanName());
			sb.append(")");
		}
		if (andFilterWithInterfaceName) {
			sb.append(")");
		}
		String filter = sb.toString();
		if (StringUtils.hasText(filter)) {
			filterStringForServiceLookup = filter;
			return filterStringForServiceLookup;
		}
		else {
			return null;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	public void destroy() throws Exception {
		if (listener != null) {
			bundleContext.removeServiceListener(listener);
		}
		unbind();
	}

	/**
	 * @return Returns the filter.
	 */
	public String getFilter() {
		return filter;
	}

	/**
	 * @param filter The filter to set.
	 */
	public void setFilter(String filter) {
		this.filter = filter;
	}

	/**
	 * @return Returns the timeout.
	 */
	public long getTimeout() {
		return timeout;
	}

	/**
	 * @param timeout The timeout to set.
	 */
	public void setTimeout(long timeout) {
		this.timeout = timeout;
		// This is kind of a hack
		if (timeout > 0)
			setRetryTimes((int) (timeout / getRetryDelayMs()));
	}

	/**
	 * @return Returns the listeners.
	 */
	public TargetSourceLifecycleListener[] getListeners() {
		return defensiveCopyOf(this.listeners);
	}

	/**
	 * @param listeners The listeners to set.
	 */
	public void setListeners(TargetSourceLifecycleListener[] listeners) {
		this.listeners = defensiveCopyOf(listeners);
	}

	private TargetSourceLifecycleListener[] defensiveCopyOf(TargetSourceLifecycleListener[] original) {
		if (null == original) {
			return new TargetSourceLifecycleListener[0];
		}
		else {
			TargetSourceLifecycleListener[] copy = new TargetSourceLifecycleListener[original.length];
			System.arraycopy(original, 0, copy, 0, original.length);
			return copy;
		}
	}

	public void setId(String id) {
		this.id = id;
	}

	protected void bind() {
		for (int i = 0; i < listeners.length; i++) {
			try {
				listeners[i].bind(id, proxy);
			}
			catch (Throwable e) {
				logger.error("Exception in listener when binding service", e);
			}
		}
	}

	protected void unbind() {
		for (int i = 0; i < listeners.length; i++) {
			try {
				listeners[i].unbind(id, proxy);
			}
			catch (Throwable e) {
				logger.error("Exception in listener when binding service", e);
			}
		}
	}

	protected class Listener implements ServiceListener {
		private Object unavailableService;

		private HotSwappableTargetSource targetSource;

		protected Listener(Object unavailableService) {
			this.unavailableService = unavailableService;
		}

		public void serviceChanged(ServiceEvent serviceEvent) {
			ServiceReference serviceReference = serviceEvent.getServiceReference();
			switch (serviceEvent.getType()) {
			case ServiceEvent.MODIFIED:
			case ServiceEvent.REGISTERED:
				Object service = bundleContext.getService(serviceReference);
				targetSource.swap(service);
				bind();
				break;
			case ServiceEvent.UNREGISTERING:
				unbind();
				targetSource.swap(unavailableService);
				break;
			default:
			}
		}

		public void setTargetSource(HotSwappableTargetSource targetSource) {
			this.targetSource = targetSource;
		}
	}
}

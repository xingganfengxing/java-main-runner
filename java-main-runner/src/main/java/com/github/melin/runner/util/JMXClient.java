/**
 * Copyright (c) 2013 by 苏州科大国创信息技术有限公司.
 *
 * JMXClient.java Create on 2013-7-11 上午10:23:44
 */
package com.github.melin.runner.util;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.MalformedURLException;
import java.rmi.NotBoundException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MBeanServerConnection;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.QueryExp;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnector;
import javax.management.remote.rmi.RMIServer;
import javax.rmi.ssl.SslRMIClientSocketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author <a href="mailto:bsli@ustcinfo.com">li.binsong</a>
 *
 */
public class JMXClient {

    private static Logger log = LoggerFactory.getLogger(JMXClient.class);
    private final String hostName;
    private final int port;
    private final String userName;
    private final String password;

    private final JMXServiceURL address;
    private JMXConnector jmxConnector;
    private MBeanServerConnection mbs;
    private RMIServer stub = null;
	private AtomicBoolean closed = new AtomicBoolean(false);
	private AtomicInteger referenceCount = new AtomicInteger(0);

    private static Map<String, JMXClient> cache = Collections.synchronizedMap(new HashMap<String, JMXClient>());


    public static JMXClient getJMXClient(String hostName, int port) throws JMXClientException {
        return getJMXClient(hostName, port, null, null);
    }


    public static JMXClient getJMXClient(String hostName, int port, String userName, String password)
            throws JMXClientException {
        final String key = getKey(hostName, port, userName, password);
        JMXClient jmxClient = cache.get(key);
        if (jmxClient == null) {
            jmxClient = new JMXClient(hostName, port, userName, password);
            cache.put(key, jmxClient);
        }
        jmxClient.increaseReferece();
        log.debug("=============referenceCount:"+jmxClient.referenceCount.get());
        return jmxClient;
    }


    private JMXClient(String hostName, int port) throws JMXClientException {
        this(hostName, port, null, null);
    }

    private int increaseReferece(){
    	return this.referenceCount.incrementAndGet();
    }

    private int decreaseReferece(){

    	int count = this.referenceCount.decrementAndGet();
    	if(count<0){
    		this.referenceCount.set(0);
    	}
    	log.debug("=============referenceCount:"+this.referenceCount.get());
    	return this.referenceCount.get();
    }

    private JMXClient(String hostName, int port, String userName, String password) throws JMXClientException {
        this.hostName = hostName;
        this.port = port;
        this.userName = userName;
        this.password = password;
        try {
            this.address =
                    new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + this.hostName + ":" + this.port + "/jmxrmi");
        }
        catch (MalformedURLException e) {
            throw new JMXClientException(e);
        }
        this.connect();
    }
    
    public Object invoke(ObjectName name, String operationName, Object[] params, String[] signature)
            throws JMXClientException {
    	tryReconnect();
        try {
            return this.mbs.invoke(name, operationName, params, signature);
        }
        catch (Exception e) {
            throw new JMXClientException("error occurred when invoke " + name.getClass() + "#" + operationName, e);
        }
    }
    
    public ThreadMXBean getThreadMXBean() throws JMXClientException {
    	try {
    		return ManagementFactory.newPlatformMXBeanProxy  
            	    (this.mbs, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
        }
        catch (Exception e) {
            throw new JMXClientException("error occurred when get " + ThreadMXBean.class, e);
        }
    }
    
    public ObjectInstance queryMBeanForOne(String name) {
        try {
            return this.queryMBeanForOne(new ObjectName(name));
        }
        catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }


    public ObjectInstance queryMBeanForOne(ObjectName name) {
    	Set<ObjectInstance> mBeans = null;
        try {
        	tryReconnect();
            mBeans = this.mbs.queryMBeans(name, null);
        }
        catch (Exception e) {
        	log.error(e.getMessage(), e);
        }

        if (mBeans == null || mBeans.isEmpty()) {
            return null;
        }
        return mBeans.iterator().next();
    }


    public Set<ObjectInstance> queryMBeans(ObjectName name, QueryExp query) throws JMXClientException {
    	tryReconnect();
    	try {
            return this.mbs.queryMBeans(name, query);
        }
        catch (IOException e) {
            throw new JMXClientException(e);
        }
    }


    public Object getAttribute(ObjectName name, String attribute) throws JMXClientException {
    	tryReconnect();
    	try {
            return this.mbs.getAttribute(name, attribute);
        }
        catch (Exception e) {
            // 当使用通配符ObjectName时,搜索出一个
            if (log.isDebugEnabled()) {
                log.debug("没有精确的找到ObjectName = " + name + ",开始搜索..");
            }
            try {
                return this.mbs.getAttribute(this.queryMBeanForOne(name).getObjectName(), attribute);
            }
            catch (Exception e1) {
                throw new JMXClientException(e);
            }
        }
    }


    public void close() {
    	if (this.decreaseReferece()!=0) {
			return;
		}

    	if (closed.compareAndSet(false, true)) {
    		this.stub = null;
    		if (this.jmxConnector != null) {
    			try {
    				this.jmxConnector.close();

    			}
    			catch (IOException e) {
    				// ignore
    			}
    		}
    		cache.remove(getKey(this.hostName, this.port, this.userName, this.password));
    		log.debug("=================jmx closed!");
		}

    }


    public String getAddressAsString() {
        return this.address.toString();
    }

    synchronized private void tryReconnect() throws JMXClientException{
    	if(this.closed.compareAndSet(true, false)){
    		connect();
    	}
    }

    private void connect() throws JMXClientException {
        try {
            if (this.stub == null) {
                this.checkSslConfig();
            }
            this.jmxConnector = new RMIConnector(this.stub, null);
            if (this.userName == null && this.password == null) {
                // this.jmxConnector =
                // JMXConnectorFactory.connect(this.address);
                this.jmxConnector.connect();

            }
            else {
                Map<String, String[]> env = new HashMap<String, String[]>();
                env.put(JMXConnector.CREDENTIALS, new String[] { this.userName, this.password });
                // this.jmxConnector = JMXConnectorFactory.connect(this.address,
                // env);
                this.jmxConnector.connect(env);

            }
            this.mbs = this.jmxConnector.getMBeanServerConnection();
        }
        catch (Exception e) {
            throw new JMXClientException(e);
        }

    }

    private static final SslRMIClientSocketFactory sslRMIClientSocketFactory = new SslRMIClientSocketFactory();


    private void checkSslConfig() throws IOException {
        // Get the reference to the RMI Registry and lookup RMIServer stub
        Registry registry;
        try {
            registry = LocateRegistry.getRegistry(this.hostName, this.port, sslRMIClientSocketFactory);
            try {
                this.stub = (RMIServer) registry.lookup("jmxrmi");
            }
            catch (NotBoundException nbe) {
                throw (IOException) new IOException(nbe.getMessage()).initCause(nbe);
            }
        }
        catch (IOException e) {
            registry = LocateRegistry.getRegistry(this.hostName, this.port);
            try {
                this.stub = (RMIServer) registry.lookup("jmxrmi");
            }
            catch (NotBoundException nbe) {
                throw (IOException) new IOException(nbe.getMessage()).initCause(nbe);
            }
        }

    }


    private static String getKey(String hostName, int port, String userName, String password) {
        return (hostName == null ? "" : hostName) + ":" + port + ":" + (userName == null ? "" : userName) + ":"
                + (password == null ? "" : password);
    }

}

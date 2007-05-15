/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.scr;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentConstants;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.ComponentInstance;


/**
 * The default ComponentManager. Objects of this class are responsible for managing
 * implementation object's lifecycle.  
 *
 */
class ImmediateComponentManager extends AbstractComponentManager
{
    // the component ID
    private long m_componentId;

    // The object that implements the service and that is bound to other services
    private Object m_implementationObject = null;

    // The context that will be passed to the implementationObject
    private ComponentContext m_componentContext = null;
    
    // optional properties provided in the ComponentFactory.newInstance method
    private Dictionary m_factoryProperties; 
    
    // the component properties, also used as service properties
    private Dictionary m_properties; 
    
    /**
     * The constructor receives both the activator and the metadata
     * 
     * @param activator
     * @param metadata
     */
    ImmediateComponentManager(BundleComponentActivator activator, ComponentMetadata metadata, long componentId)
    {
        super(activator, metadata);
        
        m_componentId = componentId;
    }
  

    // 1. Load the component implementation class
    // 2. Create the component instance and component context
    // 3. Bind the target services
    // 4. Call the activate method, if present
    // if this method is overwritten, the deleteComponent method should
    // also be overwritten
    protected void createComponent()
    {
        ComponentContext tmpContext = new ComponentContextImpl( this );
        Object tmpComponent = createImplementationObject( tmpContext );
        
        // if something failed craeating the object, we fell back to
        // unsatisfied !!
        if (tmpComponent != null) {
            m_componentContext = tmpContext;
            m_implementationObject = tmpComponent;
        }
    }
    
    protected void deleteComponent() {
        disposeImplementationObject( m_implementationObject, m_componentContext );
        m_implementationObject = null;
        m_componentContext = null;
        m_properties = null;
    }


    //**********************************************************************************************************
    
    /**
    * Get the object that is implementing this descriptor
    *
    * @return the object that implements the services
    */
    public Object getInstance() {
        return m_implementationObject;
    }

    protected Object createImplementationObject(ComponentContext componentContext) {
        Object implementationObject;
        
        // 1. Load the component implementation class
        // 2. Create the component instance and component context
        // If the component is not immediate, this is not done at this moment
        try
        {
            // 112.4.4 The class is retrieved with the loadClass method of the component's bundle
            Class c = getActivator().getBundleContext().getBundle().loadClass(getComponentMetadata().getImplementationClassName());
            
            // 112.4.4 The class must be public and have a public constructor without arguments so component instances
            // may be created by the SCR with the newInstance method on Class
            implementationObject = c.newInstance();
        }
        catch (Exception ex)
        {
            // failed to instantiate, deactivate the component and return null
            Activator.exception( "Error during instantiation of the implementation object", getComponentMetadata(), ex );
            deactivate();
            return null;
        }
        
        
        // 3. Bind the target services
        Iterator it = getDependencyManagers();
        while ( it.hasNext() )
        {
            // if a dependency turned unresolved since the validation check,
            // creating the instance fails here, so we deactivate and return
            // null.
            DependencyManager dm = ( DependencyManager ) it.next();
            if ( !dm.bind( implementationObject ) )
            {
                Activator.error( "Cannot create component instance due to failure to bind reference " + dm.getName(),
                    getComponentMetadata() );
                deactivate();
                return null;
            }
        }
        
        // 4. Call the activate method, if present
        // Search for the activate method
        try
        {
            Method activateMethod = getMethod( implementationObject.getClass(), "activate", new Class[]
                { ComponentContext.class } );
            activateMethod.invoke( implementationObject, new Object[]
                { componentContext } );
        }
        catch ( NoSuchMethodException ex )
        {
            // We can safely ignore this one
            Activator.trace( "activate() method is not implemented", getComponentMetadata() );
        }
        catch ( IllegalAccessException ex )
        {
            // Ignored, but should it be logged?
            Activator.trace( "activate() method cannot be called", getComponentMetadata() );
        }
        catch ( InvocationTargetException ex )
        {
            // 112.5.8 If the activate method throws an exception, SCR must log an error message
            // containing the exception with the Log Service
            Activator.exception( "The activate method has thrown an exception", getComponentMetadata(), ex );
        }
        
        return implementationObject;
    }

    protected void disposeImplementationObject( Object implementationObject, ComponentContext componentContext )
    {
        // 1. Call the deactivate method, if present
        // Search for the activate method
        try
        {
            Method deactivateMethod = getMethod( implementationObject.getClass(), "deactivate", new Class[]
                { ComponentContext.class } );
            deactivateMethod.invoke( implementationObject, new Object[]
                { componentContext } );
        }
        catch ( NoSuchMethodException ex )
        {
            // We can safely ignore this one
            Activator.trace( "deactivate() method is not implemented", getComponentMetadata() );
        }
        catch ( IllegalAccessException ex )
        {
            // Ignored, but should it be logged?
            Activator.trace( "deactivate() method cannot be called", getComponentMetadata() );
        }
        catch ( InvocationTargetException ex )
        {
            // 112.5.12 If the deactivate method throws an exception, SCR must log an error message
            // containing the exception with the Log Service and continue
            Activator.exception( "The deactivate method has thrown an exception", getComponentMetadata(), ex );
        }

        // 2. Unbind any bound services
        Iterator it = getDependencyManagers();

        while ( it.hasNext() )
        {
            DependencyManager dm = ( DependencyManager ) it.next();
            dm.unbind( implementationObject );
        }

        // 3. Release all references
        // nothing to do, we keep no references on per-Bundle services
    }
    
    /**
     * Returns the service object to be registered if the service element is
     * specified.
     * <p>
     * Extensions of this class may overwrite this method to return a
     * ServiceFactory to register in the case of a delayed or a service
     * factory component. 
     */
    protected Object getService() {
        return m_implementationObject;
    }
    
    protected void setFactoryProperties(Dictionary dictionary) {
        m_factoryProperties = copyTo( null, dictionary );
    }
    
    /**
     * Returns the (private copy) of the Component properties to be used
     * for the ComponentContext as well as eventual service registration.
     * <p>
     * Method implements the Component Properties provisioning as described
     * in 112.6, Component Properties.
     * 
     * @return a private Hashtable of component properties
     */
    protected Dictionary getProperties()
    {

        // TODO: Currently on ManagedService style configuration is supported, ManagedServiceFactory style is missing

        if ( m_properties == null )
        {

            // 1. the properties from the component descriptor
            Dictionary props = copyTo( null, getComponentMetadata().getProperties() );

            // 2. overlay with Configuration Admin properties
            ConfigurationAdmin ca = getActivator().getConfigurationAdmin();
            if ( ca != null )
            {
                try
                {
                    Configuration cfg = ca.getConfiguration( getComponentMetadata().getName() );
                    if (cfg != null) {
                        copyTo( props, cfg.getProperties() );
                    }
                }
                catch ( IOException ioe )
                {
                    Activator.exception( "Problem getting Configuration", getComponentMetadata(), ioe );
                }
            }

            // 3. copy any component factory properties, not supported yet
            copyTo( props, m_factoryProperties );
            
            // 4. set component.name and component.id
            props.put( ComponentConstants.COMPONENT_NAME, getComponentMetadata().getName() );
            props.put( ComponentConstants.COMPONENT_ID, new Long( m_componentId ) );
            
            m_properties = props;
        }

        return m_properties;
    }
    
}

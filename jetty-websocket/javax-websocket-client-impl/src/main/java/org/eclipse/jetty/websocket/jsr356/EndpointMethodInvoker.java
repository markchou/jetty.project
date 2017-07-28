//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import javax.websocket.ClientEndpoint;
import javax.websocket.Endpoint;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;


public interface EndpointMethodInvoker
{
    public void invoke(Object arg);
    
    public static class Builder
    {
        // TODO convert to MethodHandle
        private final Method method;
        private final String[] paramNames;

        public Builder(
            Class<?> endpClass,
            String methodName,
            Class<?>[] methodArgs,
            Class<?> annotation,
            Set<String> parameterNames
            ) throws IllegalArgumentException
        {
            try
            {
                Method classMethod = null;
                Method annotatedMethod = null;

                if (Endpoint.class.isAssignableFrom(endpClass))
                    classMethod = Endpoint.class.getMethod(methodName,methodArgs);

                boolean annotated = endpClass.getDeclaredAnnotation(ServerEndpoint.class)!=null || endpClass.getDeclaredAnnotation(ClientEndpoint.class)!=null;

                if (annotated)
                {
                    // TODO consider private methods in the class hierarchy?
                    for (Method m : endpClass.getMethods())
                    {
                        for (Annotation a : m.getAnnotations())
                        {
                            if (annotation.isInstance(a))
                            {
                                if (annotatedMethod!=null)
                                    throw new IllegalArgumentException();
                                annotatedMethod = m;
                            }
                        }
                    }
                }

                if (classMethod!=null && annotatedMethod!=null)
                    throw new IllegalArgumentException();

                this.method = classMethod!=null ? classMethod : annotatedMethod;
                this.paramNames = method==null ? null : validate(method,parameterNames);

            }
            catch(Exception e)
            {
                throw new IllegalArgumentException(e);
            }   
        }

        private static String[] validate(Method method, Set<String> parameterNames)
        {
            AnnotatedType[] types = method.getAnnotatedParameterTypes();
            String[] names = new String[types.length];
            for (int i=0; i<types.length; i++)
            {
                PathParam pp = types[i].getAnnotation(PathParam.class);
                if (pp!=null)
                {
                    names[i] = pp.value();
                    if (names[i]==null)
                        throw new IllegalArgumentException();
                    if (!parameterNames.contains(names[i]))
                        throw new IllegalArgumentException();
                }
                
                // TODO validate either parameter or known type.
            }
            return names;
        }
        
        public EndpointMethodInvoker build(final Object instance, final Session session, Map<String,String> parameters) throws IllegalArgumentException
        {
            final Class<?>[] types = method.getParameterTypes();
            final Object[] paramValues = new Object[types.length];
            for (int i=0; i<types.length; i++)
            {
                if (paramNames[i]!=null)
                {
                    if (!parameters.containsKey(paramNames[i]))
                        throw new IllegalArgumentException();
                   
                    paramValues[i] = paramValue(types[i],parameters.get(paramNames[i]));
                }
            }
            
            return new EndpointMethodInvoker()
            {
                @Override
                public void invoke(Object arg)
                {
                    Object[] args = new Object[types.length];
                    for (int i=0; i<types.length; i++)
                    {
                        if (paramNames[i]!=null)
                            args[i] = paramValues[i];
                        else if (types[i].isAssignableFrom(Session.class))
                            args[i] = session;
                        else if (arg!=null && types[i].isAssignableFrom(arg.getClass()))
                            args[i] = arg;
                        else
                            throw new IllegalStateException();
                    }
                    
                    try
                    {
                        method.invoke(instance,args);
                    }
                    catch(Throwable th)
                    {
                        throw new IllegalStateException(th);
                    }
                }
                
            };
        }

        private static Object paramValue(Class<?> type, String string)
        {
            // TODO do this properly
            if (type == String.class)
                return string;
            if (type == Integer.class)
                return new Integer(string);
            // TODO etc.
            throw new IllegalArgumentException();
        }
    }


}

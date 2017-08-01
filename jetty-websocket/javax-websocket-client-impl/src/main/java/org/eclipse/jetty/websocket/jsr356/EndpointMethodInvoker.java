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
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import javax.websocket.ClientEndpoint;
import javax.websocket.Endpoint;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.common.util.ReflectUtils;


public interface EndpointMethodInvoker
{
    public Object invoke(Object arg);
    public Object invoke(Object arg, boolean bool);

    public static final EndpointMethodInvoker NOOP = new EndpointMethodInvoker()
    {
        @Override
        public Object invoke(Object arg)
        {
            return null;
        }

        @Override
        public Object invoke(Object arg, boolean bool)
        {
            return null;
        }
    };

    public static class Builder
    {
        private final Method method;
        private final String[] paramNames;

        public Builder(
            Class<?> endpClass,
            String methodName,
            Class<?>[] methodArgs,
            Class<? extends Annotation> annotation,
            Set<String> parameterNames
            ) throws IllegalArgumentException
        {
            try
            {
                Method classMethod = (Endpoint.class.isAssignableFrom(endpClass))
                        ?ReflectUtils.findMethod(endpClass,methodName,methodArgs):null;
                Method annotatedMethod = ReflectUtils.isAnnotatedWithDeclared(endpClass,ServerEndpoint.class,ClientEndpoint.class)
                        ?ReflectUtils.findAnnotatedMethod(endpClass,annotation):null;

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

        public Builder(Method method, Set<String> parameterNames)
        {
            this.method = method;
            this.paramNames = validate(method,parameterNames);
        }

        public Method getMethod()
        {
            return method;
        }

        public Class<?>[] getNonParameterTypes()
        {
            int n=0;
            for (int i=0; i<paramNames.length; i++)
                if (paramNames[i]==null)
                    n++;
            Class<?>[] pt = method.getParameterTypes();
            Class<?>[] types = new Class<?>[n];
            n=0;
            for (int i=0; i<paramNames.length; i++)
                if (paramNames[i]==null)
                    types[n++]=pt[i];
            return types;
        }

        private static String[] validate(Method method, Set<String> parameterNames)
        {
            Class<?>[] types = method.getParameterTypes();
            Annotation[][] annotations = method.getParameterAnnotations();

            String[] names = new String[types.length];
            for (int i=0; i<types.length; i++)
            {
                for (int j=0; j<annotations[i].length; j++)
                {
                    if (PathParam.class.isInstance(annotations[i][j]))
                    {
                        PathParam pp = (PathParam)annotations[i][j];
                        if (names[i]!=null)
                            throw new IllegalArgumentException("Duplicate annotation");
                        names[i] = pp.value();
                        if (names[i] == null)
                            throw new IllegalArgumentException();
                        if (!parameterNames.contains(names[i]))
                            throw new IllegalArgumentException();
                    }
                }

                // TODO validate either parameter or known type.
            }
            return names;
        }
        
        public EndpointMethodInvoker build(final Object instance, final Session session, Map<String,String> parameters) throws IllegalArgumentException
        {
            if (method==null)
                return NOOP;

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
                public Object invoke(Object arg)
                {
                    return invoke(arg,null);
                }

                @Override
                public Object invoke(Object arg, boolean bool)
                {
                    return invoke(arg,bool?Boolean.TRUE:Boolean.FALSE);
                }

                Object invoke(Object arg, Boolean bool)
                {
                    Object[] args = new Object[types.length];
                    for (int i=0; i<types.length; i++)
                    {
                        if (paramNames[i]!=null)
                            args[i] = paramValues[i];
                        else if (Session.class.isAssignableFrom(types[i]))
                            args[i] = session;
                        else if (bool!=null && types[i]==Boolean.class || types[i]==boolean.class)
                            args[i] = bool;
                        else if (arg!=null && types[i].isAssignableFrom(arg.getClass()))
                            args[i] = arg;
                        else if (arg!=null)
                            throw new IllegalStateException("unknown arg: "+arg+" of type "+arg.getClass());
                    }

                    try
                    {
                        return method.invoke(instance,args);
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

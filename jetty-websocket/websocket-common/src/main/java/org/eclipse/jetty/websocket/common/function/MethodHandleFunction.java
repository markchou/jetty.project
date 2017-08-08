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

package org.eclipse.jetty.websocket.common.function;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.function.Function;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;

public class MethodHandleFunction<T> implements Function<T, Void>
{
    private final Class<T> type;
    private final MethodHandle methodHandle;
    private Object args[];

    public MethodHandleFunction(Class<T> t, MethodHandle methodHandle)
    {
        this.type = t;
        this.methodHandle = methodHandle;
    }

    public MethodHandleFunction(Class<T> t, Method method)
    {
        this.type = t;
        try
        {
            this.methodHandle = MethodHandles.lookup().unreflect(method);
        }
        catch (IllegalAccessException e)
        {
            throw new InvalidWebSocketException(e.getMessage(), e);
        }
    }

    public void setArgs(Object args[])
    {
        this.args = args;
    }

    @Override
    public Void apply(T t)
    {
        args[0] = t;
        try
        {
            methodHandle.invoke(args);
        }
        catch (Throwable throwable)
        {
            // TODO: do something with??
            throwable.printStackTrace();
        }
        return null;
    }

    // TODO: should we really do this?
    public void bindTo(Object endpointInstance)
    {
        methodHandle.bindTo(endpointInstance);
    }
}

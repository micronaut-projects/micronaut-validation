/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.validation.tck;

import io.micronaut.core.annotation.Internal;

import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NameNotFoundException;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.OperationNotSupportedException;
import javax.naming.spi.InitialContextFactory;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Internal
public final class TckInitialContextFactory implements InitialContextFactory {

    private static final Map<String, Object> BINDINGS = new ConcurrentHashMap<>();

    static void bind(String name, Object value) {
        BINDINGS.put(name, value);
    }

    static void clear() {
        BINDINGS.clear();
    }

    @Override
    public Context getInitialContext(Hashtable<?, ?> environment) {
        return new TckContext();
    }

    private static final class TckContext implements Context {

        @Override
        public Object lookup(Name name) throws NamingException {
            return lookup(name.toString());
        }

        @Override
        public Object lookup(String name) throws NamingException {
            Object value = BINDINGS.get(name);
            if (value == null) {
                throw new NameNotFoundException(name);
            }
            return value;
        }

        @Override
        public void close() {
            // No resources are held by this in-memory TCK context.
        }

        @Override
        public Object lookupLink(Name name) throws NamingException {
            return lookup(name);
        }

        @Override
        public Object lookupLink(String name) throws NamingException {
            return lookup(name);
        }

        @Override
        public Hashtable<?, ?> getEnvironment() {
            return new Hashtable<>();
        }

        @Override
        public String getNameInNamespace() {
            return "";
        }

        @Override
        public void bind(Name name, Object obj) throws NamingException {
            throw unsupported("bind(Name, Object)");
        }

        @Override
        public void bind(String name, Object obj) throws NamingException {
            throw unsupported("bind(String, Object)");
        }

        @Override
        public void rebind(Name name, Object obj) throws NamingException {
            throw unsupported("rebind(Name, Object)");
        }

        @Override
        public void rebind(String name, Object obj) throws NamingException {
            throw unsupported("rebind(String, Object)");
        }

        @Override
        public void unbind(Name name) throws NamingException {
            throw unsupported("unbind(Name)");
        }

        @Override
        public void unbind(String name) throws NamingException {
            throw unsupported("unbind(String)");
        }

        @Override
        public void rename(Name oldName, Name newName) throws NamingException {
            throw unsupported("rename(Name, Name)");
        }

        @Override
        public void rename(String oldName, String newName) throws NamingException {
            throw unsupported("rename(String, String)");
        }

        @Override
        public NamingEnumeration<NameClassPair> list(Name name) throws NamingException {
            throw unsupported("list(Name)");
        }

        @Override
        public NamingEnumeration<NameClassPair> list(String name) throws NamingException {
            throw unsupported("list(String)");
        }

        @Override
        public NamingEnumeration<Binding> listBindings(Name name) throws NamingException {
            throw unsupported("listBindings(Name)");
        }

        @Override
        public NamingEnumeration<Binding> listBindings(String name) throws NamingException {
            throw unsupported("listBindings(String)");
        }

        @Override
        public void destroySubcontext(Name name) throws NamingException {
            throw unsupported("destroySubcontext(Name)");
        }

        @Override
        public void destroySubcontext(String name) throws NamingException {
            throw unsupported("destroySubcontext(String)");
        }

        @Override
        public Context createSubcontext(Name name) throws NamingException {
            throw unsupported("createSubcontext(Name)");
        }

        @Override
        public Context createSubcontext(String name) throws NamingException {
            throw unsupported("createSubcontext(String)");
        }

        @Override
        public NameParser getNameParser(Name name) throws NamingException {
            throw unsupported("getNameParser(Name)");
        }

        @Override
        public NameParser getNameParser(String name) throws NamingException {
            throw unsupported("getNameParser(String)");
        }

        @Override
        public Name composeName(Name name, Name prefix) throws NamingException {
            throw unsupported("composeName(Name, Name)");
        }

        @Override
        public String composeName(String name, String prefix) {
            return prefix + name;
        }

        @Override
        public Object addToEnvironment(String propName, Object propVal) throws NamingException {
            throw unsupported("addToEnvironment");
        }

        @Override
        public Object removeFromEnvironment(String propName) throws NamingException {
            throw unsupported("removeFromEnvironment");
        }

        private static OperationNotSupportedException unsupported(String operation) {
            return new OperationNotSupportedException(operation);
        }
    }
}

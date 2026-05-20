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
            unsupported();
        }

        @Override
        public void bind(String name, Object obj) throws NamingException {
            unsupported();
        }

        @Override
        public void rebind(Name name, Object obj) throws NamingException {
            unsupported();
        }

        @Override
        public void rebind(String name, Object obj) throws NamingException {
            unsupported();
        }

        @Override
        public void unbind(Name name) throws NamingException {
            unsupported();
        }

        @Override
        public void unbind(String name) throws NamingException {
            unsupported();
        }

        @Override
        public void rename(Name oldName, Name newName) throws NamingException {
            unsupported();
        }

        @Override
        public void rename(String oldName, String newName) throws NamingException {
            unsupported();
        }

        @Override
        public NamingEnumeration<NameClassPair> list(Name name) throws NamingException {
            unsupported();
            return null;
        }

        @Override
        public NamingEnumeration<NameClassPair> list(String name) throws NamingException {
            unsupported();
            return null;
        }

        @Override
        public NamingEnumeration<Binding> listBindings(Name name) throws NamingException {
            unsupported();
            return null;
        }

        @Override
        public NamingEnumeration<Binding> listBindings(String name) throws NamingException {
            unsupported();
            return null;
        }

        @Override
        public void destroySubcontext(Name name) throws NamingException {
            unsupported();
        }

        @Override
        public void destroySubcontext(String name) throws NamingException {
            unsupported();
        }

        @Override
        public Context createSubcontext(Name name) throws NamingException {
            unsupported();
            return null;
        }

        @Override
        public Context createSubcontext(String name) throws NamingException {
            unsupported();
            return null;
        }

        @Override
        public NameParser getNameParser(Name name) throws NamingException {
            unsupported();
            return null;
        }

        @Override
        public NameParser getNameParser(String name) throws NamingException {
            unsupported();
            return null;
        }

        @Override
        public Name composeName(Name name, Name prefix) throws NamingException {
            unsupported();
            return null;
        }

        @Override
        public String composeName(String name, String prefix) {
            return prefix + name;
        }

        @Override
        public Object addToEnvironment(String propName, Object propVal) throws NamingException {
            unsupported();
            return null;
        }

        @Override
        public Object removeFromEnvironment(String propName) throws NamingException {
            unsupported();
            return null;
        }

        private static void unsupported() throws NamingException {
            throw new OperationNotSupportedException();
        }
    }
}

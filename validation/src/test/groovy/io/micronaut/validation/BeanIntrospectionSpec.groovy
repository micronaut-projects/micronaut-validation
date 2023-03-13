package io.micronaut.validation


import io.micronaut.annotation.processing.TypeElementVisitorProcessor
import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.annotation.processing.test.JavaParser
import io.micronaut.context.annotation.Replaces
import io.micronaut.context.visitor.ConfigurationReaderVisitor
import io.micronaut.core.beans.BeanIntrospection
import io.micronaut.core.beans.BeanIntrospectionReference
import io.micronaut.core.beans.BeanProperty
import io.micronaut.inject.beans.visitor.IntrospectedTypeElementVisitor
import io.micronaut.inject.visitor.TypeElementVisitor
import io.micronaut.jackson.modules.BeanIntrospectionModule
import io.micronaut.validation.annotation.ValidatedElement
import io.micronaut.validation.visitor.IntrospectedValidationIndexesVisitor
import io.micronaut.validation.visitor.ValidationVisitor
import jakarta.inject.Singleton

import javax.annotation.processing.SupportedAnnotationTypes
import jakarta.validation.Constraint
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

class BeanIntrospectionSpec extends AbstractTypeElementSpec {

    void "test annotations on generic type arguments for Java 14+ records"() {
        given:
        BeanIntrospection introspection = buildBeanIntrospection('test.Foo', '''
package test;

import io.micronaut.core.annotation.Creator;
import java.util.List;
import jakarta.validation.constraints.Min;

@io.micronaut.core.annotation.Introspected
public record Foo(List<@Min(10) Long> value){
}
''')
        when:
        BeanProperty<?, ?> property = introspection.getRequiredProperty("value", List)
        def genericTypeArg = property.asArgument().getTypeParameters()[0]

        then:
        property != null
        genericTypeArg.annotationMetadata.hasStereotype(Constraint)
        genericTypeArg.annotationMetadata.hasAnnotation(Min)
        genericTypeArg.annotationMetadata.intValue(Min).getAsInt() == 10
    }

    void 'test annotations on generic type arguments'() {
        given:
        BeanIntrospection introspection = buildBeanIntrospection('test.Foo', '''
package test;

import io.micronaut.core.annotation.Creator;
import java.util.List;
import jakarta.validation.constraints.Min;
import java.lang.annotation.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.lang.annotation.ElementType.*;

@io.micronaut.core.annotation.Introspected
public class Foo {
    private List<@Min(10) @SomeAnn Long> value;

    public List<Long> getValue() {
        return value;
    }

    public void setValue(List<Long> value) {
        this.value = value;
    }
}

@Documented
@Retention(RUNTIME)
@Target({ METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE })
@interface SomeAnn {

}
''')
        when:
        BeanProperty<?, ?> property = introspection.getRequiredProperty("value", List)
        def genericTypeArg = property.asArgument().getTypeParameters()[0]

        then:
        property != null
        genericTypeArg.annotationMetadata.hasAnnotation(Min)
        genericTypeArg.annotationMetadata.intValue(Min).getAsInt() == 10
    }

    void "test bean introspection on a Java 14+ record"() {
        given:
        BeanIntrospection introspection = buildBeanIntrospection('test.Foo', '''
package test;

import io.micronaut.core.annotation.Creator;

@io.micronaut.core.annotation.Introspected
public record Foo(@jakarta.validation.constraints.NotBlank String name, int age){
}
''')
        when:
        def test = introspection.instantiate("test", 20)
        def property = introspection.getRequiredProperty("name", String)
        def argument = introspection.getConstructorArguments()[0]

        then:
        argument.name == 'name'
        argument.getAnnotationMetadata().hasStereotype(Constraint)
        argument.getAnnotationMetadata().hasAnnotation(NotBlank)
        test.name == 'test'
        test.name() == 'test'
        introspection.propertyNames.length == 2
        introspection.propertyNames == ['name', 'age'] as String[]
        property.hasAnnotation(NotBlank)
        property.isReadOnly()
        property.hasSetterOrConstructorArgument()
        property.name == 'name'
        property.get(test) == 'test'

        when:"a mutation is applied"
        def newTest = property.withValue(test, "Changed")

        then:"a new instance is returned"
        !newTest.is(test)
        newTest.name() == 'Changed'
        newTest.age() == 20
    }

    void "test generate bean introspection for @ConfigurationProperties interface"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.ValidatedConfig','''\
package test;

import io.micronaut.context.annotation.ConfigurationProperties;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.net.URL;

@ConfigurationProperties("foo.bar")
public interface ValidatedConfig {

    @NotNull
    URL getUrl();

}


''')
        expect:
        introspection != null
        introspection.getProperty("url")
    }

    void "test generate bean introspection for @ConfigurationProperties interface with custom getter"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.ValidatedConfig', '''\
package test;

import io.micronaut.context.annotation.ConfigurationProperties;import io.micronaut.core.annotation.AccessorsStyle;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.net.URL;

@ConfigurationProperties("foo.bar")
@AccessorsStyle(readPrefixes = {"read"})
public interface ValidatedConfig {

    @NotNull
    URL readUrl();

}


''')
        expect:
        introspection != null
        introspection.getProperty("url")
    }

    void "test generate bean introspection for @ConfigurationProperties with validation rules on getters"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.ValidatedConfig','''\
package test;

import io.micronaut.context.annotation.ConfigurationProperties;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.net.URL;

@ConfigurationProperties("foo.bar")
public class ValidatedConfig {

    private URL url;
    private String name;

    @NotNull
    public URL getUrl() {
        return url;
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    @NotBlank
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}


''')
        expect:
        introspection != null
        introspection.getProperty("url")
        introspection.getProperty("name")
    }

    void "test generate bean introspection for @ConfigurationProperties with validation rules on getters with custom getters and setters"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.ValidatedConfig', '''\
package test;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.AccessorsStyle;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.net.URL;

@ConfigurationProperties("foo.bar")
@AccessorsStyle(readPrefixes = "read", writePrefixes = "with")
public class ValidatedConfig {

    private URL url;
    private String name;

    @NotNull
    public URL readUrl() {
        return url;
    }

    public void withUrl(URL url) {
        this.url = url;
    }

    @NotBlank
    public String readName() {
        return name;
    }

    public void withName(String name) {
        this.name = name;
    }
}
''')
        expect:
        introspection != null
        introspection.getProperty("url")
        introspection.getProperty("name")
    }

    void "test generate bean introspection for @ConfigurationProperties with validation rules on getters and custom getter"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.ValidatedConfig','''\
package test;

import io.micronaut.context.annotation.ConfigurationProperties;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.net.URL;

@ConfigurationProperties("foo.bar")
@io.micronaut.core.annotation.AccessorsStyle(readPrefixes = "read", writePrefixes = "with")
public class ValidatedConfig {

    private URL url;
    private String name;

    @NotNull
    public URL readUrl() {
        return url;
    }

    public void withUrl(URL url) {
        this.url = url;
    }

    @NotBlank
    public String readName() {
        return name;
    }

    public void withName(String name) {
        this.name = name;
    }
}
''')
        expect:
        introspection != null
        introspection.getProperty("url")
        introspection.getProperty("name")
    }

    void "test generate bean introspection for @ConfigurationProperties with validation rules on getters with inner class"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.ValidatedConfig','''\
package test;

import io.micronaut.context.annotation.ConfigurationProperties;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.net.URL;

@ConfigurationProperties("foo.bar")
public class ValidatedConfig {

    private URL url;

    @NotNull
    public URL getUrl() {
        return url;
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    public static class Inner {

    }

}
''')
        expect:
        introspection != null
    }

    void "test generate bean introspection for @ConfigurationProperties with validation rules on getters with inner class and custom getter"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.ValidatedConfig','''\
package test;

import io.micronaut.context.annotation.ConfigurationProperties;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.net.URL;

@ConfigurationProperties("foo.bar")
@io.micronaut.core.annotation.AccessorsStyle(readPrefixes = "read")
public class ValidatedConfig {

    private URL url;

    @NotNull
    public URL readUrl() {
        return url;
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    public static class Inner {
    }
}
''')
        expect:
        introspection != null
    }

    void "test generate bean introspection for inner @ConfigurationProperties"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.ValidatedConfig$Another','''\
package test;

import io.micronaut.context.annotation.ConfigurationProperties;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.net.URL;

@ConfigurationProperties("foo.bar")
class ValidatedConfig {

    private URL url;

    @NotNull
    public URL getUrl() {
        return url;
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    public static class Inner {

    }

    @ConfigurationProperties("another")
    static class Another {

        private URL url;

        @NotNull
        public URL getUrl() {
            return url;
        }

        public void setUrl(URL url) {
            this.url = url;
        }
    }
}
''')
        expect:
        introspection != null
        introspection.getProperty("url")
    }

    void "test generate bean introspection for inner @ConfigurationProperties with custom getter"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.ValidatedConfig$Another', '''\
package test;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.AccessorsStyle;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.net.URL;

@ConfigurationProperties("foo.bar")
@AccessorsStyle(readPrefixes = "read")
class ValidatedConfig {

    private URL url;

    @NotNull
    public URL readUrl() {
        return url;
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    public static class Inner {
    }

    @ConfigurationProperties("another")
    @AccessorsStyle(readPrefixes = "read")
    static class Another {

        private URL url;

        @NotNull
        public URL readUrl() {
            return url;
        }

        public void setUrl(URL url) {
            this.url = url;
        }
    }
}
''')
        expect:
        introspection != null
        introspection.getProperty("url")
    }

    void "test generate bean introspection for @ConfigurationProperties with validation rules on fields"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.ValidatedConfig','''\
package test;

import io.micronaut.context.annotation.ConfigurationProperties;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.net.URL;

@ConfigurationProperties("foo.bar")
public class ValidatedConfig {

    @NotNull
    URL url;

    @NotBlank
    protected String name;

    public URL getUrl() {
        return url;
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}


''')
        expect:
        introspection != null
        !introspection.getIndexedProperties(Constraint.class).isEmpty()
        introspection.getIndexedProperties(Constraint.class).size() == 2
    }

    void "test generate bean introspection for @ConfigurationProperties with validation rules on fields and custom getter"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.ValidatedConfig', '''\
package test;

import io.micronaut.context.annotation.ConfigurationProperties;import io.micronaut.core.annotation.AccessorsStyle;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.net.URL;

@ConfigurationProperties("foo.bar")
@AccessorsStyle(readPrefixes = "read")
public class ValidatedConfig {

    @NotNull
    URL url;

    @NotBlank
    protected String name;

    public URL readUrl() {
        return url;
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    public String readName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}


''')

        expect:
        introspection != null
        !introspection.getIndexedProperties(Constraint.class).isEmpty()
        introspection.getIndexedProperties(Constraint.class).size() == 2
    }

    void "test generate bean introspection for @ConfigurationProperties with validation rules"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.MyConfig','''\
package test;

import io.micronaut.context.annotation.*;
import java.time.Duration;

@ConfigurationProperties("foo.bar")
class MyConfig {
    private String host;
    private int serverPort;

    @ConfigurationInject
    MyConfig(@jakarta.validation.constraints.NotBlank String host, int serverPort) {
        this.host = host;
        this.serverPort = serverPort;
    }

    public String getHost() {
        return host;
    }

    public int getServerPort() {
        return serverPort;
    }
}

''')
        expect:
        introspection != null
    }

    void "test generate bean introspection for @ConfigurationProperties with validation rules with custom getter"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.MyConfig', '''\
package test;

import io.micronaut.context.annotation.*;import io.micronaut.core.annotation.AccessorsStyle;
import java.time.Duration;

@ConfigurationProperties("foo.bar")
@AccessorsStyle(readPrefixes = "read")
class MyConfig {
    private String host;
    private int serverPort;

    @ConfigurationInject
    MyConfig(@jakarta.validation.constraints.NotBlank String host, int serverPort) {
        this.host = host;
        this.serverPort = serverPort;
    }

    public String readHost() {
        return host;
    }

    public int readServerPort() {
        return serverPort;
    }
}

''')

        expect:
        introspection != null
        introspection.getProperty("host")
        introspection.getProperty("serverPort")
    }

    void "test annotation metadata present on deep type parameters"() {
        BeanIntrospection introspection = buildBeanIntrospection('test.Test','''\
package test;
import io.micronaut.core.annotation.*;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;

@Introspected
public class Test {
    List<@Size(min=1, max=2) List<@NotEmpty List<@NotNull String>>> deepList;
    List<List<List<List<List<List<String>>>>>> deepList2;

    Test(List<List<List<String>>> deepList) { this.deepList = deepList; }
    List<List<List<String>>> getDeepList() { return deepList; }
    List<List<List<List<List<List<String>>>>>> getDeepList2() { return deepList2; }
}
''')
        expect:
        introspection != null
        def property = introspection.getProperty("deepList").get().asArgument()
        property.getTypeParameters().length == 1
        def param1 = property.getTypeParameters()[0]
        param1.getTypeParameters().length == 1
        def param2 = param1.getTypeParameters()[0]
        param2.getTypeParameters().length == 1
        def param3 = param2.getTypeParameters()[0]

        property.getAnnotationMetadata().getAnnotationNames().toList() == [ValidatedElement.class.name]
        param1.getAnnotationMetadata().getAnnotationNames().asList() == [ValidatedElement.class.name, 'jakarta.validation.constraints.Size$List']
        param2.getAnnotationMetadata().getAnnotationNames().asList() == [ValidatedElement.class.name, 'jakarta.validation.constraints.NotEmpty$List']
        param3.getAnnotationMetadata().getAnnotationNames().asList() == [ValidatedElement.class.name, 'jakarta.validation.constraints.NotNull$List']
    }

    void "test build introspection"() {
        given:
        def context = buildContext('test.Address', '''
package test;

import jakarta.validation.constraints.*;


@io.micronaut.core.annotation.Introspected
class Address {
    @NotBlank(groups = GroupOne.class)
    @NotBlank(groups = GroupThree.class, message = "different message")
    @Size(min = 5, max = 20, groups = GroupTwo.class)
    private String street;

    public String getStreet() {
        return this.street;
    }
}

interface GroupOne {}
interface GroupTwo {}
interface GroupThree {}
''')
        def clazz = context.classLoader.loadClass('test.$Address$IntrospectionRef')
        BeanIntrospectionReference reference = clazz.newInstance()


        expect:
        reference != null
        reference.load()
    }

    void 'test annotation on a generic field argument'() {
        when:
        BeanIntrospection beanIntrospection = buildBeanIntrospection('test.Book', '''
package test;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import io.micronaut.core.annotation.Introspected;

class Author {
}

@Introspected
class Book {

    @Size(min=2)
    private String name;

    private List<@Valid Author> authors;

    public Book(String name) {
        this.name = name;
        this.authors = null;
    }

    public Book(String name, List<Author> authors) {
        this.name = name;
        this.authors = authors;
    }

    public List<Author> getAuthors() {
        return authors;
    }

    public String getName() {
        return name;
    }
}
''')
        def property =  beanIntrospection.getBeanProperties().first()

        then:
        property.name == "authors"
        property.asArgument().getTypeParameters()[0].annotationMetadata.hasStereotype("jakarta.validation.Valid")
    }

    @Override
    protected JavaParser newJavaParser() {
        return new JavaParser() {
            @Override
            protected TypeElementVisitorProcessor getTypeElementVisitorProcessor() {
                return new MyTypeElementVisitorProcessor()
            }
        }
    }

    @SupportedAnnotationTypes("*")
    static class MyTypeElementVisitorProcessor extends TypeElementVisitorProcessor {
        @Override
        protected Collection<TypeElementVisitor> findTypeElementVisitors() {
            return [new ValidationVisitor(), new ConfigurationReaderVisitor(), new IntrospectedValidationIndexesVisitor(), new IntrospectedTypeElementVisitor()]
        }
    }

    @Singleton
    @Replaces(BeanIntrospectionModule)
    @io.micronaut.context.annotation.Requires(property = "bean.introspection.test")
    static class StaticBeanIntrospectionModule extends BeanIntrospectionModule {
        Map<Class<?>, BeanIntrospection> introspectionMap = [:]
        @Override
        protected BeanIntrospection<Object> findIntrospection(Class<?> beanClass) {
            return introspectionMap.get(beanClass)
        }
    }
}

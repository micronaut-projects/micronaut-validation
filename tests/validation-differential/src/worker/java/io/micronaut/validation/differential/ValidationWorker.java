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
package io.micronaut.validation.differential;

import io.micronaut.validation.validator.DefaultValidatorFactory;
import jakarta.validation.ClockProvider;
import jakarta.validation.Configuration;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.spi.ValidationProvider;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collection;
import java.util.Locale;
import java.util.TimeZone;

/** Separate provider process. Reflection here belongs exclusively to the test harness. */
public final class ValidationWorker {
    private ValidationWorker() { }

    public static void main(String[] arguments) throws Exception {
        Locale.setDefault(Locale.ENGLISH);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String profile = arguments[0];
        if (profile.equals("default")) {
            for (String absent : new String[]{"io.micronaut.reflection.ReflectionArguments",
                "io.micronaut.validation.reflection.ReflectionValidationSupport",
                "io.micronaut.validation.bootstrap.MicronautValidationProvider",
                "io.micronaut.validation.xml.XmlValidationMetadataProvider"}) {
                try { Class.forName(absent); throw new AssertionError("Forbidden default worker dependency: " + absent); }
                catch (ClassNotFoundException expected) { }
            }
        }
        ClockProvider clock = () -> Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);
        ValidatorFactory factory;
        if (profile.equals("default")) {
            factory = new DefaultValidatorFactory();
        } else {
            Class<? extends ValidationProvider> provider = (Class<? extends ValidationProvider>) Class.forName(profile.equals("reference")
                ? "org.hibernate.validator.HibernateValidator" : "io.micronaut.validation.bootstrap.MicronautValidationProvider");
            Configuration<?> configuration = Validation.byProvider(provider).configure().ignoreXmlConfiguration().clockProvider(clock);
            factory = configuration.buildValidatorFactory();
        }
        try (factory; var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            var validator = factory.usingContext().clockProvider(clock).getValidator();
            System.out.println("READY:" + profile);
            String request;
            while ((request = reader.readLine()) != null) {
                String[] parts = request.split("\t");
                String outcome;
                try {
                    Class<?> model = Class.forName(parts[0]);
                    var operation = model.getMethod("run", jakarta.validation.Validator.class, int.class, long.class);
                    Object result = operation.invoke(null, validator, Integer.parseInt(parts[1]), Long.parseLong(parts[2]));
                    outcome = OutcomeNormalizer.violations((Collection) result);
                } catch (Throwable failure) {
                    failure.printStackTrace(System.err);
                    outcome = OutcomeNormalizer.exception(failure);
                }
                System.out.println("OUTCOME:" + Base64.getEncoder().encodeToString(outcome.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }
}

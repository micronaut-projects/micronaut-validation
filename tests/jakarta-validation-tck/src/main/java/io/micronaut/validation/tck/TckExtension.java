package io.micronaut.validation.tck;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.core.spi.LoadableExtension;

/**
 * TCK loadable extension.
 */
public class TckExtension implements LoadableExtension {

    @Override
    public void register(ExtensionBuilder builder) {
//        SLF4JBridgeHandler.removeHandlersForRootLogger();
//        SLF4JBridgeHandler.install();
//        Logger.getLogger("").setLevel(Level.FINEST);

        builder.service(DeployableContainer.class, TckDeployableContainer.class);
//        builder.service(Protocol.class, OdiProtocol.class);
//        builder.service(TestEnricher.class, OdiInjectionEnricher.class);
    }

}

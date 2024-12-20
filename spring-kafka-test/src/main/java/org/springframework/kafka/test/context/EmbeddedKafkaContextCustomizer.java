/*
 * Copyright 2017-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.test.context;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * The {@link ContextCustomizer} implementation for the {@link EmbeddedKafkaBroker} bean registration.
 *
 * @author Artem Bilan
 * @author Elliot Metsger
 * @author Zach Olauson
 * @author Oleg Artyomov
 * @author Sergio Lourenco
 * @author Pawel Lozinski
 * @author Seonghwan Lee
 *
 * @since 1.3
 */
class EmbeddedKafkaContextCustomizer implements ContextCustomizer {

	private final EmbeddedKafka embeddedKafka;

	private final String TRANSACTION_STATE_LOG_REPLICATION_FACTOR = "transaction.state.log.replication.factor";

	EmbeddedKafkaContextCustomizer(EmbeddedKafka embeddedKafka) {
		this.embeddedKafka = embeddedKafka;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		Assert.isInstanceOf(DefaultSingletonBeanRegistry.class, beanFactory);

		ConfigurableEnvironment environment = context.getEnvironment();

		String[] topics =
				Arrays.stream(this.embeddedKafka.topics())
						.map(environment::resolvePlaceholders)
						.toArray(String[]::new);

		int[] ports = setupPorts();
		EmbeddedKafkaBroker embeddedKafkaBroker;
		if (this.embeddedKafka.kraft()) {
			embeddedKafkaBroker = new EmbeddedKafkaKraftBroker(this.embeddedKafka.count(),
					this.embeddedKafka.partitions(),
					topics)
				.kafkaPorts(ports);
		}
		else {
			embeddedKafkaBroker = new EmbeddedKafkaZKBroker(this.embeddedKafka.count(),
						this.embeddedKafka.controlledShutdown(),
						this.embeddedKafka.partitions(),
						topics)
					.kafkaPorts(ports)
					.zkPort(this.embeddedKafka.zookeeperPort())
					.zkConnectionTimeout(this.embeddedKafka.zkConnectionTimeout())
					.zkSessionTimeout(this.embeddedKafka.zkSessionTimeout());
		}

		Properties properties = new Properties();

		for (String pair : this.embeddedKafka.brokerProperties()) {
			if (!StringUtils.hasText(pair)) {
				continue;
			}
			try {
				properties.load(new StringReader(environment.resolvePlaceholders(pair)));
			}
			catch (Exception ex) {
				throw new IllegalStateException("Failed to load broker property from [" + pair + "]", ex);
			}
		}

		if (StringUtils.hasText(this.embeddedKafka.brokerPropertiesLocation())) {
			String propertiesLocation = environment.resolvePlaceholders(this.embeddedKafka.brokerPropertiesLocation());
			Resource propertiesResource = context.getResource(propertiesLocation);
			if (!propertiesResource.exists()) {
				throw new IllegalStateException(
						"Failed to load broker properties from [" + propertiesResource + "]: resource does not exist.");
			}
			try (InputStream in = propertiesResource.getInputStream()) {
				Properties p = new Properties();
				p.load(in);
				p.forEach((key, value) -> properties.putIfAbsent(key, environment.resolvePlaceholders((String) value)));
			}
			catch (IOException ex) {
				throw new IllegalStateException("Failed to load broker properties from [" + propertiesResource + "]", ex);
			}
		}

		properties.putIfAbsent(TRANSACTION_STATE_LOG_REPLICATION_FACTOR, String.valueOf(Math.min(3, embeddedKafka.count())));

		embeddedKafkaBroker.brokerProperties((Map<String, String>) (Map<?, ?>) properties);
		if (StringUtils.hasText(this.embeddedKafka.bootstrapServersProperty())) {
			embeddedKafkaBroker.brokerListProperty(this.embeddedKafka.bootstrapServersProperty());
		}

		// Safe to start an embedded broker eagerly before context refresh
		embeddedKafkaBroker.afterPropertiesSet();

		((BeanDefinitionRegistry) beanFactory).registerBeanDefinition(EmbeddedKafkaBroker.BEAN_NAME,
				new RootBeanDefinition(EmbeddedKafkaBroker.class, () -> embeddedKafkaBroker));
	}

	private int[] setupPorts() {
		int[] ports = this.embeddedKafka.ports();
		if (this.embeddedKafka.count() > 1 && ports.length == 1 && ports[0] == 0) {
			ports = new int[this.embeddedKafka.count()];
		}
		return ports;
	}

	@Override
	public int hashCode() {
		return this.embeddedKafka.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null || obj.getClass() != getClass()) {
			return false;
		}
		EmbeddedKafkaContextCustomizer customizer = (EmbeddedKafkaContextCustomizer) obj;
		return this.embeddedKafka.equals(customizer.embeddedKafka);
	}

}


/*
 * Copyright 2014-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.session.data.gemfire;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.geode.cache.Cache;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientRegionShortcut;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.data.gemfire.CacheFactoryBean;
import org.springframework.data.gemfire.client.ClientCacheFactoryBean;
import org.springframework.data.gemfire.client.PoolFactoryBean;
import org.springframework.data.gemfire.server.CacheServerFactoryBean;
import org.springframework.data.gemfire.support.ConnectionEndpoint;
import org.springframework.session.ExpiringSession;
import org.springframework.session.data.gemfire.config.annotation.web.http.EnableGemFireHttpSession;
import org.springframework.session.data.gemfire.support.GemFireUtils;
import org.springframework.session.events.AbstractSessionEvent;
import org.springframework.session.events.SessionCreatedEvent;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.events.SessionExpiredEvent;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.SocketUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests to test the functionality of GemFire-backed Spring Sessions using
 * the GemFire client-server topology.
 *
 * @author John Blum
 * @since 1.1.0
 * @see org.junit.Test
 * @see org.junit.runner.RunWith
 * @see org.springframework.context.ConfigurableApplicationContext
 * @see org.springframework.session.ExpiringSession
 * @see org.springframework.session.data.gemfire.AbstractGemFireIntegrationTests
 * @see org.springframework.session.data.gemfire.config.annotation.web.http.EnableGemFireHttpSession
 * @see org.springframework.session.data.gemfire.config.annotation.web.http.GemFireHttpSessionConfiguration
 * @see org.springframework.test.annotation.DirtiesContext
 * @see org.springframework.test.context.ContextConfiguration
 * @see org.springframework.test.context.junit4.SpringRunner
 * @see org.springframework.test.context.web.WebAppConfiguration
 * @see org.apache.geode.cache.Cache
 * @see org.apache.geode.cache.Region
 * @see org.apache.geode.cache.client.ClientCache
 * @see org.apache.geode.cache.client.Pool
 * @see org.apache.geode.cache.server.CacheServer
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes =
	ClientServerGemFireOperationsSessionRepositoryIntegrationTests.SpringSessionDataGemFireClientConfiguration.class)
@DirtiesContext
@WebAppConfiguration
public class ClientServerGemFireOperationsSessionRepositoryIntegrationTests
		extends AbstractGemFireIntegrationTests {

	private static final int MAX_INACTIVE_INTERVAL_IN_SECONDS = 1;

	private static final DateFormat TIMESTAMP = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");

	private static File processWorkingDirectory;

	private static Process gemfireServer;

	private static final String SPRING_SESSION_GEMFIRE_REGION_NAME = "TestClientServerSessions";

	@Autowired
	private SessionEventListener sessionEventListener;

	@BeforeClass
	public static void startGemFireServer() throws IOException {
		long t0 = System.currentTimeMillis();

		int port = SocketUtils.findAvailableTcpPort();

		System.err.printf("Starting a GemFire Server running on host [%1$s] listening on port [%2$d]%n",
			SpringSessionDataGemFireServerConfiguration.SERVER_HOSTNAME, port);

		System.setProperty("spring.session.data.gemfire.port", String.valueOf(port));

		String processWorkingDirectoryPathname =
			String.format("gemfire-client-server-tests-%1$s", TIMESTAMP.format(new Date()));

		processWorkingDirectory = createDirectory(processWorkingDirectoryPathname);

		gemfireServer = run(SpringSessionDataGemFireServerConfiguration.class, processWorkingDirectory,
			String.format("-Dspring.session.data.gemfire.port=%1$d", port));

		assertThat(waitForCacheServerToStart(SpringSessionDataGemFireServerConfiguration.SERVER_HOSTNAME, port))
			.isTrue();

		System.err.printf("GemFire Server [startup time = %1$d ms]%n", System.currentTimeMillis() - t0);
	}

	@AfterClass
	public static void stopGemFireServer() {
		if (gemfireServer != null) {
			gemfireServer.destroy();
			System.err.printf("GemFire Server [exit code = %1$d]%n",
				waitForProcessToStop(gemfireServer, processWorkingDirectory));
		}

		if (Boolean.valueOf(System.getProperty("spring.session.data.gemfire.fork.clean", Boolean.TRUE.toString()))) {
			FileSystemUtils.deleteRecursively(processWorkingDirectory);
		}

		assertThat(waitForClientCacheToClose(DEFAULT_WAIT_DURATION)).isTrue();
	}

	@Before
	public void setup() {
		assertThat(GemFireUtils.isClient(gemfireCache)).isTrue();

		Region<Object, ExpiringSession> springSessionGemFireRegion =
			gemfireCache.getRegion(SPRING_SESSION_GEMFIRE_REGION_NAME);

		assertThat(springSessionGemFireRegion).isNotNull();

		RegionAttributes<Object, ExpiringSession> springSessionGemFireRegionAttributes =
			springSessionGemFireRegion.getAttributes();

		assertThat(springSessionGemFireRegionAttributes).isNotNull();
		assertThat(springSessionGemFireRegionAttributes.getDataPolicy()).isEqualTo(DataPolicy.NORMAL);
	}

	@After
	public void tearDown() {
		this.sessionEventListener.getSessionEvent();
	}

	@Test
	public void createSessionFiresSessionCreatedEvent() {
		long beforeOrAtCreationTime = System.currentTimeMillis();

		ExpiringSession expectedSession = save(createSession());

		AbstractSessionEvent sessionEvent = this.sessionEventListener.waitForSessionEvent(500);

		assertThat(sessionEvent).isInstanceOf(SessionCreatedEvent.class);

		ExpiringSession createdSession = sessionEvent.getSession();

		assertThat(createdSession.getId()).isEqualTo(expectedSession.getId());
		assertThat(createdSession.getCreationTime()).isGreaterThanOrEqualTo(beforeOrAtCreationTime);
		assertThat(createdSession.getLastAccessedTime()).isEqualTo(createdSession.getCreationTime());
		assertThat(createdSession.getMaxInactiveIntervalInSeconds()).isEqualTo(MAX_INACTIVE_INTERVAL_IN_SECONDS);

		createdSession.setAttribute("attrOne", 1);

		assertThat(save(touch(createdSession)).<Integer>getAttribute("attrOne")).isEqualTo(1);

		sessionEvent = this.sessionEventListener.waitForSessionEvent(500);

		assertThat(sessionEvent).isNull();

		this.gemfireSessionRepository.delete(expectedSession.getId());
	}

	@Test
	public void getExistingNonExpiredSessionBeforeAndAfterExpiration() {
		ExpiringSession expectedSession = save(touch(createSession()));

		AbstractSessionEvent sessionEvent = this.sessionEventListener.waitForSessionEvent(500);

		assertThat(sessionEvent).isInstanceOf(SessionCreatedEvent.class);
		assertThat(sessionEvent.<ExpiringSession>getSession()).isEqualTo(expectedSession);
		assertThat(this.sessionEventListener.<SessionCreatedEvent>getSessionEvent()).isNull();

		ExpiringSession savedSession = this.gemfireSessionRepository.getSession(expectedSession.getId());

		assertThat(savedSession).isEqualTo(expectedSession);

		sessionEvent = this.sessionEventListener.waitForSessionEvent(
			TimeUnit.SECONDS.toMillis(MAX_INACTIVE_INTERVAL_IN_SECONDS + 1));

		assertThat(sessionEvent).isInstanceOf(SessionExpiredEvent.class);
		assertThat(sessionEvent.getSessionId()).isEqualTo(expectedSession.getId());

		ExpiringSession expiredSession = this.gemfireSessionRepository.getSession(expectedSession.getId());

		assertThat(expiredSession).isNull();
	}

	@Test
	public void deleteExistingNonExpiredSessionFiresSessionDeletedEventAndReturnsNullOnGet() {
		ExpiringSession expectedSession = save(touch(createSession()));

		AbstractSessionEvent sessionEvent = this.sessionEventListener.waitForSessionEvent(500);

		assertThat(sessionEvent).isInstanceOf(SessionCreatedEvent.class);
		assertThat(sessionEvent.<ExpiringSession>getSession()).isEqualTo(expectedSession);

		this.gemfireSessionRepository.delete(expectedSession.getId());

		sessionEvent = this.sessionEventListener.waitForSessionEvent(500);

		assertThat(sessionEvent).isInstanceOf(SessionDeletedEvent.class);
		assertThat(sessionEvent.getSessionId()).isEqualTo(expectedSession.getId());

		ExpiringSession deletedSession = this.gemfireSessionRepository.getSession(expectedSession.getId());

		assertThat(deletedSession).isNull();
	}

	@EnableGemFireHttpSession(regionName = SPRING_SESSION_GEMFIRE_REGION_NAME,
		clientRegionShortcut = ClientRegionShortcut.CACHING_PROXY,
			maxInactiveIntervalInSeconds = MAX_INACTIVE_INTERVAL_IN_SECONDS)
	static class SpringSessionDataGemFireClientConfiguration {

		@Bean
		static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
			return new PropertySourcesPlaceholderConfigurer();
		}

		Properties gemfireProperties() {
			Properties gemfireProperties = new Properties();
			gemfireProperties.setProperty("log-level", GEMFIRE_LOG_LEVEL);
			return gemfireProperties;
		}

		@Bean
		ClientCacheFactoryBean gemfireCache() {
			ClientCacheFactoryBean clientCacheFactory = new ClientCacheFactoryBean();

			clientCacheFactory.setClose(true);
			clientCacheFactory.setProperties(gemfireProperties());

			return clientCacheFactory;
		}

		@Bean
		PoolFactoryBean gemfirePool(
				@Value("${spring.session.data.gemfire.port:" + DEFAULT_GEMFIRE_SERVER_PORT + "}") int port) {

			PoolFactoryBean poolFactory = new PoolFactoryBean();

			poolFactory.setKeepAlive(false);
			poolFactory.setPingInterval(TimeUnit.SECONDS.toMillis(5));
			poolFactory.setReadTimeout(2000); // 2 seconds
			poolFactory.setRetryAttempts(1);
			poolFactory.setSubscriptionEnabled(true);

			poolFactory.setServers(Collections.singletonList(new ConnectionEndpoint(
				SpringSessionDataGemFireServerConfiguration.SERVER_HOSTNAME, port)));

			return poolFactory;
		}

		@Bean
		public SessionEventListener sessionEventListener() {
			return new SessionEventListener();
		}

		// used for debugging purposes
		@SuppressWarnings("resource")
		public static void main(String[] args) {
			ConfigurableApplicationContext applicationContext = new AnnotationConfigApplicationContext(
					SpringSessionDataGemFireClientConfiguration.class);

			applicationContext.registerShutdownHook();

			ClientCache clientCache = applicationContext.getBean(ClientCache.class);

			for (InetSocketAddress server : clientCache.getCurrentServers()) {
				System.err.printf("GemFire Server [host: %1$s, port: %2$d]%n",
					server.getHostName(), server.getPort());
			}
		}
	}

	@EnableGemFireHttpSession(regionName = SPRING_SESSION_GEMFIRE_REGION_NAME,
		maxInactiveIntervalInSeconds = MAX_INACTIVE_INTERVAL_IN_SECONDS)
	static class SpringSessionDataGemFireServerConfiguration {

		static final String SERVER_HOSTNAME = "localhost";

		@Bean
		static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
			return new PropertySourcesPlaceholderConfigurer();
		}

		Properties gemfireProperties() {
			Properties gemfireProperties = new Properties();

			gemfireProperties.setProperty("name", name());
			gemfireProperties.setProperty("mcast-port", "0");
			gemfireProperties.setProperty("log-level", GEMFIRE_LOG_LEVEL);

			return gemfireProperties;
		}

		String name() {
			return ClientServerGemFireOperationsSessionRepositoryIntegrationTests.class.getName();
		}

		@Bean
		CacheFactoryBean gemfireCache() {
			CacheFactoryBean gemfireCache = new CacheFactoryBean();

			gemfireCache.setClose(true);
			gemfireCache.setProperties(gemfireProperties());

			return gemfireCache;
		}

		@Bean
		CacheServerFactoryBean gemfireCacheServer(Cache gemfireCache,
				@Value("${spring.session.data.gemfire.port:" + DEFAULT_GEMFIRE_SERVER_PORT + "}") int port) {

			CacheServerFactoryBean cacheServerFactory = new CacheServerFactoryBean();

			cacheServerFactory.setCache(gemfireCache);
			cacheServerFactory.setAutoStartup(true);
			cacheServerFactory.setBindAddress(SERVER_HOSTNAME);
			cacheServerFactory.setPort(port);

			return cacheServerFactory;
		}

		@SuppressWarnings("resource")
		public static void main(String[] args) throws IOException {
			AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(SpringSessionDataGemFireServerConfiguration.class);

			context.registerShutdownHook();

			writeProcessControlFile(WORKING_DIRECTORY);
		}
	}
}

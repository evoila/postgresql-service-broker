/**
 * 
 */
package de.evoila.cf.broker.service.custom;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import de.evoila.cf.broker.persistence.mongodb.repository.ClusterStackMapping;
import de.evoila.cf.broker.persistence.mongodb.repository.StackMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import de.evoila.cf.broker.exception.ServiceBrokerException;
import de.evoila.cf.broker.model.Plan;
import de.evoila.cf.broker.model.RouteBinding;
import de.evoila.cf.broker.model.ServerAddress;
import de.evoila.cf.broker.model.ServiceInstance;
import de.evoila.cf.broker.model.ServiceInstanceBinding;
import de.evoila.cf.broker.service.impl.BindingServiceImpl;
import de.evoila.cf.broker.service.postgres.PostgresCustomImplementation;
import de.evoila.cf.broker.service.postgres.jdbc.PostgresDbService;

/**
 * @author Johannes Hiemer.
 *
 */
@Service
public class PostgreSQLBindingService extends BindingServiceImpl {

	private Logger log = LoggerFactory.getLogger(getClass());

	private PostgresCustomImplementation postgresCustomImplementation;
	private PostgreSQLExistingServiceFactory existingServiceFactory;
	private Optional<StackMappingRepository> stackMappingRepository;

	PostgreSQLBindingService(PostgresCustomImplementation customImplementation, PostgreSQLExistingServiceFactory existingServiceFactory, Optional<StackMappingRepository> stackMappingRepository){
		Assert.notNull(customImplementation, "PostgresCustomImplementation may not be null");
		Assert.notNull(existingServiceFactory, "PostgreSQLExistingServiceFactory may not be null");
		this.existingServiceFactory = existingServiceFactory;
		this.postgresCustomImplementation = customImplementation;
		this.stackMappingRepository = stackMappingRepository;
	}
	

	private PostgresDbService connection(ServiceInstance serviceInstance) throws SQLException {
		Assert.notNull(serviceInstance, "ServiceInstance may not be null");
		String serviceInstanceId = serviceInstance.getId();
		Assert.notNull(serviceInstanceId, "Id of ServiceInstance may not be null");
		Assert.notNull(serviceInstance.getHosts(), "Host of ServiceInstance may not be null");

		ServerAddress host = serviceInstance.getHosts().get(0);
		PostgresDbService jdbcService = new PostgresDbService();
		jdbcService.createConnection(host.getIp(), host.getPort(), serviceInstanceId, serviceInstanceId, serviceInstanceId);
		return jdbcService;
	}
	
	private PostgresDbService setUpAdminConnection(String database) throws SQLException {
		PostgresDbService jdbcService = new PostgresDbService();
		jdbcService.createConnection(existingServiceFactory.getHosts().get(0), existingServiceFactory.getPort(), database, existingServiceFactory.getUsername(), existingServiceFactory.getPassword());
		return jdbcService;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * de.evoila.cf.broker.service.impl.BindingServiceImpl#createCredentials(
	 * java.lang.String, de.evoila.cf.broker.model.ServiceInstance,
	 * de.evoila.cf.broker.model.ServerAddress)
	 */
	@Override
	protected Map<String, Object> createCredentials(String bindingId, ServiceInstance serviceInstance,
			ServerAddress host) throws ServiceBrokerException {
		PostgresDbService jdbcService;
		try {
			jdbcService = connection(serviceInstance);
		} catch (SQLException e1) {
			throw new ServiceBrokerException("Could not connect to database");
		}
		
		String username = bindingId;
		String password = "";
		String hostIp = host.getIp();
		int hostPort = host.getPort();
		String database = serviceInstance.getId();
		
		try {
			password = postgresCustomImplementation.bindRoleToDatabaseAndGeneratePassword(jdbcService, serviceInstance.getId(), bindingId);
			jdbcService.closeIfConnected();
			jdbcService.createConnection(serviceInstance.getHosts().get(0).getIp(), serviceInstance.getHosts().get(0).getPort(), serviceInstance.getId(), bindingId, password);
			postgresCustomImplementation.setUpBindingUserPrivileges(jdbcService, serviceInstance.getId(), bindingId, password);
		} catch (SQLException e) {
			throw new ServiceBrokerException("Could not update database");
		} finally {
            jdbcService.closeIfConnected();
        }

		String dbURL = String.format("postgres://%s:%s@%s:%d/%s", username, password, hostIp, hostPort,
				database);

		Map<String, Object> credentials = new HashMap<String, Object>();
		credentials.put("uri", dbURL);
		credentials.put("username", bindingId);
		credentials.put("password", password);
		credentials.put("host", hostIp);
		credentials.put("port", hostPort);
		credentials.put("database", database);

		return credentials;
	}

	@Override
	protected void deleteBinding(String bindingId, ServiceInstance serviceInstance) throws ServiceBrokerException {
		PostgresDbService jdbcService;
		try {

			if(stackMappingRepository.isPresent() && stackMappingRepository.get().exists(serviceInstance.getId())){
				// If the service Instance is a Openstack instance
				ClusterStackMapping stackMapping = stackMappingRepository.get().findOne(serviceInstance.getId());
				jdbcService = setUpAdminConnection(serviceInstance,stackMapping);
			} else {
				jdbcService = setUpAdminConnection(serviceInstance.getId());
			}
		} catch (SQLException e1) {
			throw new ServiceBrokerException("Could not connect to database");
		}

		try {
			postgresCustomImplementation.unbindRoleFromDatabase(jdbcService, bindingId, serviceInstance.getId());
		} catch (SQLException e) {
			log.error(e.toString());
			throw new ServiceBrokerException("Could not remove from database");
		} finally {
            jdbcService.closeIfConnected();
        }
	}

	private PostgresDbService setUpAdminConnection (ServiceInstance serviceInstance, ClusterStackMapping stackMapping) {
		PostgresDbService jdbcService = new PostgresDbService();
		String serviceInstanceId = serviceInstance.getId();
		boolean connected = false;
		int i = 0;
		while(connected == false && i< stackMapping.getServerAddresses().size()){  // try to connect to all hosts
			ServerAddress address = stackMapping.getServerAddresses().get(i);
			i++;
			connected = jdbcService.createConnection(address.getIp(),address.getPort(),serviceInstanceId,serviceInstanceId,serviceInstanceId);
		}
		return jdbcService;
	}

	@Override
	public ServiceInstanceBinding getServiceInstanceBinding(String id) {
		throw new UnsupportedOperationException();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * de.evoila.cf.broker.service.impl.BindingServiceImpl#bindRoute(de.evoila.
	 * cf.broker.model.ServiceInstance, java.lang.String)
	 */
	@Override
	protected RouteBinding bindRoute(ServiceInstance serviceInstance, String route) {
		throw new UnsupportedOperationException();
	}

}

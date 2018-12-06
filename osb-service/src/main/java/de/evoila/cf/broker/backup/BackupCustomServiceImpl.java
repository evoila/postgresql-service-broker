package de.evoila.cf.broker.backup;

import de.evoila.cf.broker.bean.BackupConfiguration;
import de.evoila.cf.broker.custom.postgres.PostgresCustomImplementation;
import de.evoila.cf.broker.custom.postgres.PostgresDbService;
import de.evoila.cf.broker.exception.PlatformException;
import de.evoila.cf.broker.exception.ServiceBrokerException;
import de.evoila.cf.broker.exception.ServiceDefinitionDoesNotExistException;
import de.evoila.cf.broker.exception.ServiceInstanceDoesNotExistException;
import de.evoila.cf.broker.model.Platform;
import de.evoila.cf.broker.model.catalog.plan.Plan;
import de.evoila.cf.broker.model.ServiceInstance;
import de.evoila.cf.broker.repository.ServiceDefinitionRepository;
import de.evoila.cf.broker.repository.ServiceInstanceRepository;
import de.evoila.cf.broker.service.BackupCustomService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@Service
@ConditionalOnBean(BackupConfiguration.class)
public class BackupCustomServiceImpl implements BackupCustomService {

    BackupConfiguration backupTypeConfiguration;

    ServiceInstanceRepository serviceInstanceRepository;

    PostgresCustomImplementation postgresCustomImplementation;

    ServiceDefinitionRepository serviceDefinitionRepository;

    public BackupCustomServiceImpl(BackupConfiguration backupTypeConfiguration,
                                   ServiceInstanceRepository serviceInstanceRepository,
                                   PostgresCustomImplementation postgresCustomImplementation,
                                   ServiceDefinitionRepository serviceDefinitionRepository) {
        this.backupTypeConfiguration = backupTypeConfiguration;
        this.serviceInstanceRepository = serviceInstanceRepository;
        this.postgresCustomImplementation = postgresCustomImplementation;
        this.serviceDefinitionRepository = serviceDefinitionRepository;
    }

    @Override
    public Map<String, String> getItems(String serviceInstanceId) throws ServiceInstanceDoesNotExistException,
            ServiceDefinitionDoesNotExistException {
        ServiceInstance instance = this.validateServiceInstanceId(serviceInstanceId);

        Plan plan = serviceDefinitionRepository.getPlan(instance.getPlanId());

        PostgresDbService postgresDbService = postgresCustomImplementation.connection(instance, plan,
                instance.getId(), true);

        Map<String, String> result = new HashMap<>();
        try {
            Map<String, String> databases = postgresDbService.executeSelect("SELECT datname FROM pg_database", "datname");

            for(Map.Entry<String, String> database : databases.entrySet())
                result.put(database.getValue(), database.getValue());
        } catch(SQLException ex) {
            new ServiceBrokerException("Could not load databases", ex);
        }

        return result;
    }

    @Override
    public void createItem(String serviceInstanceId, String name, Map<String, Object> parameters) throws ServiceInstanceDoesNotExistException,
            ServiceDefinitionDoesNotExistException, ServiceBrokerException {
        ServiceInstance instance = this.validateServiceInstanceId(serviceInstanceId);

        Plan plan = serviceDefinitionRepository.getPlan(instance.getPlanId());

        PostgresDbService postgresDbService = postgresCustomImplementation.connection(instance, plan);

        try {
            postgresCustomImplementation.createDatabase(postgresDbService, name);
        } catch (PlatformException ex) {
            throw new ServiceBrokerException("Could not create Database", ex);
        }
    }

    private ServiceInstance validateServiceInstanceId(String serviceInstanceId) throws ServiceInstanceDoesNotExistException {
        ServiceInstance instance = serviceInstanceRepository.getServiceInstance(serviceInstanceId);

        if(instance == null || instance.getHosts().size() <= 0) {
            throw new ServiceInstanceDoesNotExistException(serviceInstanceId);
        }

        return instance;
    }

}

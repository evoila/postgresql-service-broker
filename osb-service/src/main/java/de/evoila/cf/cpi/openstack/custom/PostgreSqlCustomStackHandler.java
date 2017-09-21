/**
 * 
 */
package de.evoila.cf.cpi.openstack.custom;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.openstack4j.model.heat.Stack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import de.evoila.cf.broker.bean.OpenstackBean;
import de.evoila.cf.broker.exception.PlatformException;
import de.evoila.cf.broker.model.ServerAddress;
import de.evoila.cf.broker.persistence.mongodb.repository.ClusterStackMapping;
import de.evoila.cf.broker.persistence.mongodb.repository.StackMappingRepository;

/**
 * @author Yannic Remmet, evoila
 *
 */
@Service
@ConditionalOnBean(OpenstackBean.class)
public class PostgreSqlCustomStackHandler extends CustomStackHandler {
	
	private static final String PRE_IP_TEMPLATE = "/openstack/pre-ips.yaml";
	private static final String PRE_VOLUME_TEMPLATE = "/openstack/pre-volume.yaml";
	
	private static final String MAIN_TEMPLATE = "/openstack/main.yaml";
	
	private static final String PRIMARY_TEMPLATE = "/openstack/primary.yaml";
	private static final String SECONDARY_TEMPLATE = "/openstack/secondaries.yaml";
	
	private static final String NAME_TEMPLATE = "postgresql-%s-%s";
	
	private static final String PORTS_KEY = "port_ids";
	private static final String IP_ADDRESS_KEY = "port_ips";
	private static final String VOLUME_KEY = "volume_ids";
	
	private String keyPair;
	
	private final Logger log = LoggerFactory.getLogger(PostgreSqlCustomStackHandler.class);

	@Autowired
	private StackMappingRepository stackMappingRepo;
	
	@Autowired
	private OpenstackBean openstackBean;
	
	public PostgreSqlCustomStackHandler() {
		super();
	}
	
	@PostConstruct
	private void initValues() {
		keyPair = openstackBean.getKeypair();
	}
	
	@Override
	public void delete(String internalId) {
		ClusterStackMapping stackMapping;
		stackMapping = stackMappingRepo.findOne(internalId);

		if (stackMapping == null) {
			super.delete(internalId);
		} else {
			
			try {
				super.deleteAndWait(stackMapping.getPrimaryStack());
				Thread.sleep(60000);
			} catch (PlatformException | InterruptedException e) {
				log.error("Could not delete Stack " + stackMapping.getPrimaryStack() + " Instance " + internalId);
				log.error(e.getMessage());
			}
			super.delete(stackMapping.getPortsStack());
			super.delete(stackMapping.getVolumeStack());

			stackMappingRepo.delete(stackMapping);
		}
	}

	@Override
	public String create(String instanceId, Map<String, String> customParameters)
			throws PlatformException, InterruptedException {
		log.debug(customParameters.toString());
		if (customParameters.containsKey(PostgreSqlParameterManager.CLUSTER)) {
			log.debug("Start creating cluster " + instanceId);
			ClusterStackMapping clusterStacks = createCluster(instanceId, customParameters);
			log.debug("End creating cluster " + instanceId);
			stackMappingRepo.save(clusterStacks);
			return clusterStacks.getId();
		} 
		log.debug("Not Creating a cluster" + instanceId);
		return super.create(instanceId, customParameters);
	}

	private ClusterStackMapping createCluster(String instanceId, Map<String, String> customParameters)
			throws PlatformException, InterruptedException {

		log.debug("Start create a Postgres cluster");
		customParameters.putAll(this.defaultParameters());
		customParameters.putAll(this.generateValues(instanceId));
		
		ClusterStackMapping stackMapping = new ClusterStackMapping();
		stackMapping.setId(instanceId);
		
		
	
		Stack ipStack = createPreIpStack(instanceId, customParameters);
		stackMapping.setPortsStack(ipStack.getId());
		stackMappingRepo.save(stackMapping);

		
		List<String>[] responses = extractResponses(ipStack, PORTS_KEY, IP_ADDRESS_KEY);
		List<String> ips = responses[1];
		List<String> ports = responses[0];
		
		for (int i = 0; i < ips.size(); i++) {
			String ip = ips.get(i);
			stackMapping.addServerAddress(new ServerAddress("node-" + i, ip, 5432));
		}
		
		PostgreSqlParameterManager.updatePortParameters(customParameters, ips, ports);
		Stack preVolumeStack = createPreVolumeStack(instanceId, customParameters);
		stackMapping.setVolumeStack(preVolumeStack.getId());
		stackMappingRepo.save(stackMapping);

		responses = extractResponses(preVolumeStack, VOLUME_KEY);
		List<String> volumes = responses[0];
		PostgreSqlParameterManager.updateVolumeParameters(customParameters, volumes);
		
		Stack mainStack = createMainStack(instanceId, customParameters);
		
		
		stackMapping.setPrimaryStack(mainStack.getId());
		stackMappingRepo.save(stackMapping);

		return stackMapping;
	}

	private Map<? extends String, ? extends String> generateValues(String instanceId) {
		HashMap<String, String> valueMap = new HashMap<String, String>();
		valueMap.put(PostgreSqlParameterManager.ADMIN_PASSWORD, instanceId);
		valueMap.put(PostgreSqlParameterManager.ADMIN_USER, instanceId);
		valueMap.put(PostgreSqlParameterManager.SERVICE_DB, instanceId);
		valueMap.put(PostgreSqlParameterManager.KEY_NAME, keyPair);
		valueMap.put(PostgreSqlParameterManager.STANDBY_HOSTNAME, instanceId);
		valueMap.put(PostgreSqlParameterManager.STANDBY_ACTIVE, "0");
		return valueMap;
	}
	
	private Stack createMainStack(String instanceId, Map<String, String> customParameters) throws PlatformException {
		Map<String, String> parametersMain = PostgreSqlParameterManager.copyProperties(
				customParameters,
				PostgreSqlParameterManager.RESOURCE_NAME,
				PostgreSqlParameterManager.IMAGE_ID,
				PostgreSqlParameterManager.KEY_NAME,
				PostgreSqlParameterManager.FLAVOR,
				PostgreSqlParameterManager.AVAILABILITY_ZONE,

				PostgreSqlParameterManager.PRIMARY_VOLUME_ID,
				PostgreSqlParameterManager.PRIMARY_IP,
				PostgreSqlParameterManager.PRIMARY_PORT,
				
				PostgreSqlParameterManager.STANDBY_VOLUME_ID,
				PostgreSqlParameterManager.STANDBY_IP,
				PostgreSqlParameterManager.STANDBY_PORT,

				PostgreSqlParameterManager.SERVICE_DB,
				PostgreSqlParameterManager.ADMIN_USER,
				PostgreSqlParameterManager.ADMIN_PASSWORD
				
				//ParameterManager.RESOURCE_NAME,
				//ParameterManager.NODE_NUMBER


				);
	
		String name = String.format(NAME_TEMPLATE, instanceId, "cl");
		parametersMain.put(PostgreSqlParameterManager.RESOURCE_NAME, name);
		
		String template = accessTemplate(MAIN_TEMPLATE);
		String primary = accessTemplate(PRIMARY_TEMPLATE);
		String secondaries = accessTemplate(SECONDARY_TEMPLATE);
		
		Map<String, String> files = new HashMap<String, String>();
		files.put("primary.yaml", primary);
		files.put("secondaries.yaml", secondaries);
		
		heatFluent.create(name, template, parametersMain, false, 10l, files);
		Stack stack = stackProgressObserver.waitForStackCompletion(name);
		return stack;
	}
	
	
	private List<String>[] extractResponses(Stack stack, String... keys) {
		List<String>[] response = new List[keys.length];
		
		for (Map<String, Object> output : stack.getOutputs()) {
			Object outputKey = output.get("output_key");
			if (outputKey != null && outputKey instanceof String) {
				String key = (String) outputKey;
				for(int i=0; i< keys.length; i++) {
					if (key.equals(keys[i])) {
						response[i] = (List<String>) output.get("output_value");
					}
				}	
			}
		}
		return response;
	}
	
	private Stack createPreVolumeStack(String instanceId, Map<String, String> customParameters) throws PlatformException {
		Map<String, String> parametersPreVolume = PostgreSqlParameterManager.copyProperties(
				customParameters, 
				PostgreSqlParameterManager.RESOURCE_NAME,
				PostgreSqlParameterManager.NODE_NUMBER,
				PostgreSqlParameterManager.VOLUME_SIZE
				);
		
		String name = String.format(NAME_TEMPLATE, instanceId, "vol");
		parametersPreVolume.put(PostgreSqlParameterManager.RESOURCE_NAME, name);

		String templatePorts = accessTemplate(PRE_VOLUME_TEMPLATE);
		heatFluent.create(name, templatePorts, parametersPreVolume, false, 10l);
		Stack preVolumeStack = stackProgressObserver.waitForStackCompletion(name);
		return preVolumeStack;
	}
	
	

	private Stack createPreIpStack(String instanceId, Map<String, String> customParameters) throws PlatformException {
		Map<String, String> parametersPreIP = PostgreSqlParameterManager.copyProperties(customParameters, 
				PostgreSqlParameterManager.RESOURCE_NAME,
				PostgreSqlParameterManager.NODE_NUMBER,
				PostgreSqlParameterManager.NETWORK_ID,
				PostgreSqlParameterManager.SECURITY_GROUPS
				);
		String name = String.format(NAME_TEMPLATE, instanceId, "ip");
		parametersPreIP.put(PostgreSqlParameterManager.RESOURCE_NAME, name);
		//parametersPreIP.put(ParameterManager.SECURITY_GROUPS, "cf-private");
		
		String templatePorts = accessTemplate(PRE_IP_TEMPLATE);
		
		heatFluent.create(name, templatePorts, parametersPreIP, false, 10l);

		Stack preIpStack = stackProgressObserver.waitForStackCompletion(name);
		return preIpStack;
	}
}

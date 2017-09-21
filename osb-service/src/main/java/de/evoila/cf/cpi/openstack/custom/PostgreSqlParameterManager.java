/**
 * 
 */
package de.evoila.cf.cpi.openstack.custom;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PostgreSqlParameterManager {

	public static final String RESOURCE_NAME = "resource_name";
	public static final String NODE_NUMBER = "node_number";
	
	public static final String VOLUME_SIZE = "volume_size";
	public static final String KEY_NAME = "key_name";
	public static final String IMAGE_ID = "image_id";
	public static final String AVAILABILITY_ZONE = "availability_zone";	
	public static final String NETWORK_ID = "network_id";
	public static final String SECURITY_GROUPS = "security_groups";
	
	public static final String FLAVOR = "flavor";
	public static final String SERVICE_DB = "service_db";
	public static final String ADMIN_USER = "admin_user";
	public static final String ADMIN_PASSWORD = "admin_password";
	
	public static final String PRIMARY_HOSTNAME = "primary_hostname";
	public static final String PRIMARY_VOLUME_ID  = "primary_volume_id";
	public static final String PRIMARY_IP = "primary_ip";
	public static final String PRIMARY_PORT = "primary_port";
	public static final String STANDBY_HOSTNAME = "standby_hostname";
	public static final String STANDBY_VOLUME_ID = "standby_volume_id";
	public static final String STANDBY_IP = "standby_ip";
	public static final String STANDBY_PORT = "standby_port";
	public static final String STANDBY_ACTIVE = "standby_active";

	public static final String CLUSTER = "cluster";

	static void updatePortParameters(Map<String, String> customParameters, List<String> ips, List<String> ports) {
		String primIp = ips.remove(0);
		String primPort = ports.remove(0);
		
		customParameters.put(PostgreSqlParameterManager.PRIMARY_PORT, primPort);
		customParameters.put(PostgreSqlParameterManager.PRIMARY_IP, primIp);

		customParameters.put(PostgreSqlParameterManager.STANDBY_PORT, join(ports));
		customParameters.put(PostgreSqlParameterManager.STANDBY_IP, join(ips));
	}
	
	static void updateVolumeParameters(Map<String, String> customParameters, List<String> volumes) {
		String primaryVolume = volumes.get(0);
		volumes.remove(0);
		
		customParameters.put(PostgreSqlParameterManager.PRIMARY_VOLUME_ID, primaryVolume);
		customParameters.put(PostgreSqlParameterManager.STANDBY_VOLUME_ID, join(volumes));
	}
	
	static int getSecondaryNumber(Map<String, String> customParameters) {
		return Integer.parseInt(customParameters.get(PostgreSqlParameterManager.NODE_NUMBER));
	}

	public static String join(List<String> volumes) {
		StringBuilder b = new StringBuilder();
		for (String volume_id : volumes) {
			b.append(volume_id);
			if(!volumes.get(volumes.size()-1).equals(volume_id)) {
				b.append(",");
			}
		}
		return b.toString();
	}

	static Map<String, String> copyProperties(Map<String, String> completeList, String... keys) {
		Map<String, String> copiedProps = new HashMap<>();

		for (int i = 0; i < keys.length; i++) {
			String key = keys[i];
			copiedProps.put(key, completeList.get(key));
		}
		return copiedProps;
	}
}
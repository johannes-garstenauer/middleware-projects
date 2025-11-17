package mw.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MWServiceInstanceManager {

    private MWRegistryClient registryClient;
    private String groupName;
    private String serviceName;

    public MWServiceInstanceManager(String registryIp, String groupName, String serviceName) {
        this.groupName = groupName;
        this.serviceName = serviceName;
        this.registryClient = new MWRegistryClient(registryIp);
    }

    public void autoLogin() {
        registryClient.autoLogin();
        postLogin();
    }

    private void postLogin() {
        // ensure service is create (createService() is idempotent)
        try {
            registryClient.createService(groupName, serviceName);
        } catch (MWWebServiceException e) {
            e.printStackTrace();
        }
    }

    public void addInstance(String ip) throws MWWebServiceException {
        if (ip == null || ip.isEmpty()) {
            throw new MWWebServiceException("No ip was given");
        }

        registryClient.putValue(groupName, serviceName, getUniqueAddrKey(ip), ip);
    }

    public void removeInstance(String ip) throws MWWebServiceException {
        if (ip == null || ip.isEmpty()) {
            throw new MWWebServiceException("No ip was given");
        }

        registryClient.deleteValue(groupName, serviceName, getUniqueAddrKey(ip));
    }

    public List<String> listInstances(RegistryCache cache) throws MWWebServiceException {
        String[] keys = registryClient.listKeys(groupName, serviceName);

        // registry can contains all sorts of keys, sort out non-address values
        String[] addrKeys = Arrays.stream(keys).filter(key -> key.startsWith("addr-")).toArray(String[]::new);
        ArrayList<String> instances = new ArrayList<>(addrKeys.length);

        for (int i = 0; i < addrKeys.length; i++) {
            // retrieve the ip either cached or uncached
            String ip;
            if (cache == null) {
                ip = registryClient.getValue(groupName, serviceName, addrKeys[i]);
            } else {
                ip = cache.getValue(registryClient, groupName, serviceName, addrKeys[i]);
            }

            instances.add(ip);
        }

        return instances;
    }

    private String getUniqueAddrKey(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ip.getBytes());

            // convert hash to hex string
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (int i = 0; i < hash.length; i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if(hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            // return unique key
            return "addr-" + hexString;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    static public class RegistryCache {
        private HashMap<String, String> ipCache = new HashMap<>();

        public RegistryCache() {}

        private String getValue(MWRegistryClient registryClient, String group, String service, String key)
            throws MWWebServiceException {
            
            if (key.startsWith("addr-")) {
                if (ipCache.containsKey(key)) {
                    // key already cached, yay!
                    return ipCache.get(key);
                }
            }

            // key not cacheable or value not cached yet
            // retrieve the value from the registry now

            String value = registryClient.getValue(group, service, key);
            if (key.startsWith("addr-")) {
                // value is cacheable, let's save it in our cache
                ipCache.put(key, value);
            }

            return value;
        }

        public void clear() {
            ipCache.clear();
        }
    }

}

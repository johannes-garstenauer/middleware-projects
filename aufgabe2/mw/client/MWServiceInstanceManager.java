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

    public MWServiceInstanceManager(String registryUrl, String groupName, String serviceName) {
        this.groupName = groupName;
        this.serviceName = serviceName;
        this.registryClient = new MWRegistryClient(registryUrl);
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

    public void addInstance(String url) throws MWWebServiceException {
        if (url == null || url.isEmpty()) {
            throw new MWWebServiceException("No url was given");
        }

        registryClient.putValue(groupName, serviceName, getUniqueAddrKey(url), url);
    }

    public void removeInstance(String url) throws MWWebServiceException {
        if (url == null || url.isEmpty()) {
            throw new MWWebServiceException("No url was given");
        }

        registryClient.deleteValue(groupName, serviceName, getUniqueAddrKey(url));
    }

    public List<String> listInstances(RegistryCache cache) throws MWWebServiceException {
        String[] keys = registryClient.listKeys(groupName, serviceName);

        // registry can contains all sorts of keys, sort out non-address values
        String[] addrKeys = Arrays.stream(keys).filter(key -> key.startsWith("addr-")).toArray(String[]::new);
        ArrayList<String> instances = new ArrayList<>(addrKeys.length);

        for (int i = 0; i < addrKeys.length; i++) {
            // retrieve the url either cached or uncached
            String url;
            if (cache == null) {
                url = registryClient.getValue(groupName, serviceName, addrKeys[i]);
            } else {
                url = cache.getValue(registryClient, groupName, serviceName, addrKeys[i]);
            }

            instances.add(url);
        }

        return instances;
    }

    private String getUniqueAddrKey(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(url.getBytes());

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
        private HashMap<String, String> urlCache = new HashMap<>();

        public RegistryCache() {}

        private String getValue(MWRegistryClient registryClient, String group, String service, String key)
            throws MWWebServiceException {
            
            if (key.startsWith("addr-")) {
                String hash = key.substring("addr-".length());
                if (urlCache.containsKey(hash)) {
                    // key already cached, yay!
                    return urlCache.get(hash);
                }
            }

            // key not cacheable or value not cached yet
            // retrieve the value from the registry now

            String value = registryClient.getValue(group, service, key);
            if (key.startsWith("addr-")) {
                // value is cacheable, let's save it in our cache
                urlCache.put(key, value);
            }

            return value;
        }

        public void clear() {
            urlCache.clear();
        }
    }

}

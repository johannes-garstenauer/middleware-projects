package mw.hybridcloud;

import java.util.ArrayList;
import java.util.List;
import mw.client.*;

public class MWTweetTargetSource {
	MWServiceInstanceManager instanceManager;
	MWServiceInstanceManager.RegistryCache cache;

	public MWTweetTargetSource() {
		instanceManager = new MWServiceInstanceManager(MWRegistryClient.readRegistryURL(), "gruppe1", "tweet");
		instanceManager.autoLogin();

		cache = new MWServiceInstanceManager.RegistryCache();
	}

	public List<String> queryTweetTargets() {
		try {
			return instanceManager.listInstances(cache);
		} catch (MWWebServiceException e) {
			System.err.println("Encountered exception during listInstance(): " + e.getMessage());
			e.printStackTrace();
		}

		return new ArrayList<>();
	}
}

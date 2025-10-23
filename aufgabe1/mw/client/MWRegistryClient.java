package mw.client;


public class MWRegistryClient extends MWShell {

	// ##########
	// # GROUPS #
	// ##########

	public String[] listGroups() throws MWWebServiceException {
		/*
		 * TODO: Implement method
		 */
		return null;
	}


	// ############
	// # SERVICES #
	// ############

	public String[] listServices(String group) throws MWWebServiceException {
		/*
		 * TODO: Implement method
		 */
		return null;
	}

	public void createService(String group, String service) throws MWWebServiceException {
		/*
		 * TODO: Implement method
		 */
	}

	public void deleteService(String group, String service) throws MWWebServiceException {
		/*
		 * TODO: Implement method
		 */
	}


	// ###########
	// # ENTRIES #
	// ###########

	public String[] listKeys(String group, String service) throws MWWebServiceException {
		/*
		 * TODO: Implement method
		 */
		return null;
	}

	public String getValue(String group, String service, String key) throws MWWebServiceException {
		/*
		 * TODO: Implement method
		 */
		return null;
	}

	public void putValue(String group, String service, String key, String value) throws MWWebServiceException {
		/*
		 * TODO: Implement method
		 */
	}

	public void deleteValue(String group, String service, String key) throws MWWebServiceException {
		/*
		 * TODO: Implement method
		 */
	}


	// ###################
	// # PATH STATISTICS #
	// ###################

	private void printPathStatistics() throws MWWebServiceException {
		System.out.println("  Group  |  #IDs  | #Calls");
		System.out.println("---------+--------+--------");
		String[] groups = listGroups();
		for(String group: groups) {
			// Get ID statistics
			String numberOfIDs;
			try {
				numberOfIDs = getValue(group, "path", "number-of-ids");
			} catch(MWWebServiceException wse) {
				numberOfIDs = "";
			}

			// Get call statistics
			String numberOfCalls;
			try {
				numberOfCalls = getValue(group, "path", "number-of-calls");
			} catch(MWWebServiceException wse) {
				numberOfCalls = "";
			}

			// Print statistics
			if(numberOfIDs.isEmpty() && numberOfCalls.isEmpty()) continue;
			System.out.println(String.format("%-8s | %6s | %6s", group, numberOfIDs, numberOfCalls));
		}
	}


	// #########
	// # SHELL #
	// #########

	@Override
	protected boolean processCommand(String[] args) throws MWWebServiceException {
		switch(args[0]) {
		case "help":
		case "h":
			System.out.println("The following commands are available:\n"
					+ "  help\n"
					+ "  list-groups\n"
					+ "  list-services <group>\n"
					+ "  create-service <group> <service>\n"
					+ "  delete-service <group> <service>\n"
					+ "  list-keys <group> <service>\n"
					+ "  get-value <group> <service> <key>\n"
					+ "  put-value <group> <service> <key> <value>\n"
					+ "  delete-value <group> <service> <key>\n"
					+ "  path-statistics\n"
					+ "  quit"
			);
			break;
		case "list-groups":
		case "lg":
			String[] groups = listGroups();
			for(String group: groups) System.out.println(group);
			break;
		case "list-services":
		case "ls":
			if(args.length < 2) throw new IllegalArgumentException("Usage: list-services <group>");
			String[] services = listServices(args[1]);
			for(String service: services) System.out.println(service);
			break;
		case "create-service":
		case "cs":
			if(args.length < 3) throw new IllegalArgumentException("Usage: create-service <group> <service>");
			createService(args[1], args[2]);
			break;
		case "delete-service":
		case "ds":
			if(args.length < 3) throw new IllegalArgumentException("Usage: delete-service <group> <service>");
			deleteService(args[1], args[2]);
			break;
		case "list-keys":
		case "l":
			if(args.length < 3) throw new IllegalArgumentException("Usage: list-keys <group> <service>");
			String[] keys = listKeys(args[1], args[2]);
			for(String key: keys) System.out.println(key);
			break;
		case "get-value":
		case "g":
			if(args.length < 4) throw new IllegalArgumentException("Usage: get-value <group> <service> <key>");
			String value = getValue(args[1], args[2], args[3]);
			System.out.println(value);
			break;
		case "put-value":
		case "p":
			if(args.length < 5) throw new IllegalArgumentException("Usage: put-value <group> <service> <key> <value>");
			putValue(args[1], args[2], args[3], args[4]);
			break;
		case "delete-value":
		case "d":
			if(args.length < 4) throw new IllegalArgumentException("Usage: delete-value <group> <service> <key>");
			deleteValue(args[1], args[2], args[3]);
			break;
		case "path-statistics":
		case "ps":
			printPathStatistics();
			break;
		case "exit":
		case "quit":
		case "x":
		case "q":
			return false;
		default:
			throw new IllegalArgumentException("Unknown command: " + args[0] + "\nUse \"help\" to list available commands");
		}
		return true;
	}


	// ########
	// # MAIN #
	// ########

	public static void main(String[] args) {
		MWRegistryClient registry = new MWRegistryClient();
		registry.shell();
	}

}

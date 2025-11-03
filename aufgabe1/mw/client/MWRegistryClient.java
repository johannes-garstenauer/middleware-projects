package mw.client;

import javax.ws.rs.*;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.glassfish.jersey.*;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;

import java.io.*;
import java.net.URI;
import java.util.Scanner;
import java.util.function.IntPredicate;

public class MWRegistryClient extends MWShell {
    WebTarget client;

    public MWRegistryClient(String url) {
        URI uri = UriBuilder.fromUri(url).build();
        client = ClientBuilder.newClient().target(uri);
    }

    public void login(String username, String password) {
        HttpAuthenticationFeature af = HttpAuthenticationFeature.basic(username, password);
        client.register(af);
    }

    /**
     * Choose either loginViaFile if possible or loginViaCLI if no file
     * with credentials was found
     */
    public void autoLogin() {
        if (!loginViaFile()) {
            System.out.println("credentials.txt not found! Falling back to CLI login...");
            loginViaCLI();
        }
    }

    public boolean loginViaFile() {
        String[] filePaths = {
                "aufgabe1/credentials.txt",
                "credentials.txt"
        };

        File file = null;
        for (String filePath : filePaths) {
            File curr = new File(filePath);
            if (curr.isFile()) {
                file = curr;
                break;
            }
        }

        if (file == null) {
            // no file found
            return false;
        }

        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String username = reader.readLine();
            String password = reader.readLine();

            if (password == null) {
                // end of file detected
                return false;
            }
            login(username, password);
            return true;
        } catch (IOException e) {
            System.err.println("Warning: Could not read auth.txt!");
            System.err.println(e.getMessage());
            return false;
        }
    }

    public void loginViaCLI() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Please login to use the registry client!");
        System.out.print("> Username: ");
        String username = scanner.nextLine();
        System.out.print("> Password: ");
        String password = scanner.nextLine();
        login(username, password);
    }

	// ##########
	// # GROUPS #
	// ##########

	public String[] listGroups() throws MWWebServiceException {
        Response response = client.path("/").request().get();
        GenericType<String[]> type = new GenericType<>() {};
        return response.readEntity(type);
	}

	// ############
	// # SERVICES #
	// ############

	public String[] listServices(String group) throws MWWebServiceException {
        validateArgument(group, "group");

        Response response = client.path("/" + group).request().get();
        if (response.getStatus() == 404) {
            throw new MWWebServiceException("Group " + group + " not found");
        }
        requireOK(response);

        GenericType<String[]> type = new GenericType<>() {};
        return response.readEntity(type);
	}

	public void createService(String group, String service) throws MWWebServiceException {
        validateArgument(group, "group");
        validateArgument(service, "service");

        Response response = client.path("/" + group + "/" + service).request()
                .put(Entity.text(""));
        handleServiceModifyResponse(response);
    }

	public void deleteService(String group, String service) throws MWWebServiceException {
        validateArgument(group, "group");
        validateArgument(service, "service");

        Response response = client.path("/" + group + "/" + service).request().delete();
        handleServiceModifyResponse(response);
    }

    private void handleServiceModifyResponse(Response response)
            throws MWWebServiceException {

        if (response.getStatus() == 401) {
            throw new MWWebServiceException("Username or password invalid");
        } else if (response.getStatus() == 403) {
            throw new MWWebServiceException(
                    "User is not allowed to modify entries of the group");
        } else if (response.getStatus() == 404) {
            throw new MWWebServiceException("Group not found");
        }
        requireOK(response);
    }


	// ###########
	// # ENTRIES #
	// ###########

	public String[] listKeys(String group, String service) throws MWWebServiceException {
        validateArgument(group, "group");
        validateArgument(service, "service");

        Response response = client.path("/" + group + "/" + service).request().get();
        if (response.getStatus() == 404) {
            throw new MWWebServiceException("Group or service not found");
        }
        requireOK(response);

        GenericType<String[]> type = new GenericType<>() {};
        return response.readEntity(type);
	}

	public String getValue(String group, String service, String key) throws MWWebServiceException {
        validateArgument(group, "group");
        validateArgument(service, "service");
        validateArgument(key, "key");

        Response response = client.path("/" + group + "/" + service + "/" + key)
                .request().get();
        if (response.getStatus() == 404) {
            throw new MWWebServiceException("Group or service not found");
        }
        requireOK(response);

        return response.readEntity(String.class);
	}

    public void putValue(String group, String service, String key, String value) throws MWWebServiceException {
        validateArgument(group, "group");
        validateArgument(service, "service");
        validateArgument(key, "key");

        Response response = client.path("/" + group + "/" + service + "/" + key)
                .request().put(Entity.text(value));
        if (response.getStatus() == 413) {
            throw new MWWebServiceException("Value is too large (>65536 bytes)!");
        }
        handleValueModifyResponse(response);
	}

	public void deleteValue(String group, String service, String key) throws MWWebServiceException {
        validateArgument(group, "group");
        validateArgument(service, "service");
        validateArgument(key, "key");

        Response response = client.path("/" + group + "/" + service + "/" + key)
                .request().delete();
        handleValueModifyResponse(response);
	}

    private void handleValueModifyResponse(Response response)
            throws MWWebServiceException {

        if (response.getStatus() == 401) {
            throw new MWWebServiceException("Username or password invalid");
        } else if (response.getStatus() == 403) {
            throw new MWWebServiceException(
                    "User is not allowed to modify entries of the group");
        } else if (response.getStatus() == 404) {
            throw new MWWebServiceException("Group or service not found");
        }
        requireOK(response);
    }


    /**
     * Validates arguments, such as group name and service name to
     * prevent user from typing in paths.
     * Throws an MWWebServiceException if validation failed.
     * @param arg Argument to check (such as group name, service name)
     * @param name Name of argument. Will be displayed in the exception
     *             if validation failed
     */
    private static void validateArgument(String arg, String name)
            throws MWWebServiceException {
        IntPredicate isValidChar = charcode ->
                Character.isLetterOrDigit(charcode) || charcode == '-' || charcode == '_';

        if (arg.isEmpty() || !arg.chars().allMatch(isValidChar)) {
            throw new MWWebServiceException(
                    "Validation for argument \"" + name+ "\" failed: \"" + arg + "\"");
        }
    }
    
    private static void requireOK(Response response)
        throws MWWebServiceException {
        if (response.getStatus() != 200) {
            throw new MWWebServiceException("Unexpected response status: "
                    + response.getStatus());
        }
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
		case "help", "h" -> {
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
        }
		case "list-groups", "lg" -> {
            String[] groups = listGroups();
            for (String group : groups) System.out.println(group);
        }
		case "list-services", "ls" -> {
            if (args.length < 2) throw new IllegalArgumentException("Usage: list-services <group>");
            String[] services = listServices(args[1]);
            for (String service : services) System.out.println(service);
        }
		case "create-service", "cs" -> {
            if (args.length < 3) throw new IllegalArgumentException("Usage: create-service <group> <service>");
            createService(args[1], args[2]);
        }
		case "delete-service", "ds" -> {
            if (args.length < 3) throw new IllegalArgumentException("Usage: delete-service <group> <service>");
            deleteService(args[1], args[2]);
        }
		case "list-keys", "l" -> {
            if (args.length < 3) throw new IllegalArgumentException("Usage: list-keys <group> <service>");
            String[] keys = listKeys(args[1], args[2]);
            for (String key : keys) System.out.println(key);
        }
		case "get-value", "g" -> {
            if (args.length < 4) throw new IllegalArgumentException("Usage: get-value <group> <service> <key>");
            String value = getValue(args[1], args[2], args[3]);
            System.out.println(value);
        }
		case "put-value", "p" -> {
            if (args.length < 5) throw new IllegalArgumentException("Usage: put-value <group> <service> <key> <value>");
            putValue(args[1], args[2], args[3], args[4]);
        }
		case "delete-value", "d" -> {
            if (args.length < 4) throw new IllegalArgumentException("Usage: delete-value <group> <service> <key>");
            deleteValue(args[1], args[2], args[3]);
        }
		case "path-statistics", "ps" -> printPathStatistics();
		case "exit", "quit", "x", "q" -> {
            return false;
        }
		default ->
			throw new IllegalArgumentException("Unknown command: " + args[0] + "\nUse \"help\" to list available commands");
		}
		return true;
	}


	// ########
	// # MAIN #
	// ########

    public static String readRegistryURL() {
        String[] filePaths = {
            "/proj/i4mw/pub/aufgabe1/registry.address",
            "aufgabe1/registry.address",
            "registry.address"
        };

        File file = null;
        for (String filePath : filePaths) {
            File curr = new File(filePath);
            if (curr.isFile()) {
                file = curr;
                break;
            }
        }

        if (file == null) {
            System.err.println("Error: registry.address was not found!");
            System.exit(1);
            return null;
        }

        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            return reader.readLine();
        } catch (IOException e) {
            System.err.println("Error: registry.address could not be read!");
            System.err.println(e.getMessage());
            System.exit(1);
            return null;
        }
    }

	public static void main(String[] args) {
        MWRegistryClient registry = new MWRegistryClient(readRegistryURL());
        registry.autoLogin();
        registry.shell();
	}

}

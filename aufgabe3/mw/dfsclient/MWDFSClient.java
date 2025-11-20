package mw.dfsclient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

public class MWDFSClient {
	// JAX-RS HTTP Client to reach namenode specified at command line
	private final WebTarget namenode;

	public MWDFSClient(String namenode_str) {
		Client client = ClientBuilder.newClient();
		namenode = client.target("http://" + namenode_str + "/namenode");
	}


	// ##############################
	// # LOW-LEVEL PROTOCOL HELPERS #
	// ##############################

	private void uploadBlock(/* Put arguments here */) throws MWWebServiceException {
		// FIXME: Implement
	}

	private byte[] downloadBlock(/* Put arguments here */) throws MWWebServiceException {
		// FIXME: Implement
		return null;
	}


	// ###########################
	// # COMMAND IMPLEMENTATIONS #
	// ###########################

	private void listFiles() throws MWWebServiceException {
		// FIXME: Implement
	}

	private void uploadFile(String path, int replicas) throws MWWebServiceException {
		// FIXME: Implement
	}

	private void downloadFile(String path) throws MWWebServiceException {
		// FIXME: Implement
	}

	private void removeFile(String path) throws MWWebServiceException {
		// FIXME: Implement
	}


	// #########
	// # SHELL #
	// #########

	public void shell() {
		// Create input reader and process commands
		BufferedReader commandLine = new BufferedReader(new InputStreamReader(System.in));
		while(true) {
			// Print prompt
			System.out.print("[DFS]> ");
			System.out.flush();

			// Read next line
			String command;
			try {
				command = commandLine.readLine();
			} catch(IOException ioe) {
				break;
			}
			if(command == null) break;
			if(command.isEmpty()) continue;

			// Prepare command
			String[] args = splitUnescapeArgs(command);
			if(args.length == 0) continue;
			args[0] = args[0].toLowerCase();

			try {
				// Process command
				boolean loop = processCommand(args);
				if(!loop) break;
			} catch(IllegalArgumentException iae) {
				System.err.println(iae.getMessage());
			} catch(MWWebServiceException wse) {
				System.err.println("Web service error: " + wse.getMessage());
			}
		}

		// Close input reader
		try {
			commandLine.close();
		} catch(IOException ioe) {
			// Ignore
		}
	}

	// Internal function for argument parsing. Do not use.
	private static String[] splitUnescapeArgs(String x) {
		boolean quoted = false, escaping = false;
		LinkedList<String> result = new LinkedList<>();
		StringBuilder sb = new StringBuilder();

		for (int i = 0; i < x.length(); i++) {
			Character c = x.charAt(i);

			if (escaping) {
				escaping = false;
				sb.append(c);
				continue;
			}

			if (c == '\\') { escaping = true; continue; }
			if (c == '"')  { quoted = ! quoted; continue; }
			if (Character.isWhitespace(c) && ! quoted) {
				if (sb.length() == 0) continue;

				result.add(sb.toString());
				sb = new StringBuilder();
				continue;
			}
			sb.append(c);
		}
		if (sb.length() > 0) result.add(sb.toString());
		return result.toArray(new String[0]);
	}

	private boolean processCommand(String[] args) throws MWWebServiceException {
		switch(args[0]) {
			case "help":
			case "h":
				System.out.println("The following commands are available:\n"
						+ "  help                     Print this text\n"
						+ "  list                     List current directory content\n"
						+ "  upload <file> [replicas] Uploads file to current directory\n"
						+ "  download <file>          Download given file\n"
						+ "  remove <file>            Delete given file\n"
						+ "  quit                     Exit this program"
				);
				break;
			case "list":
			case "ls":
				if (args.length > 1) throw new IllegalArgumentException("Usage: list");
				listFiles();
				break;
			case "upload":
			case "up":
			case "u":
				if(args.length < 2 || args.length > 3) throw new IllegalArgumentException("Usage: upload <file> [replicas]");

				int replicas = 1;
				if (args.length == 3) replicas = tryParseInt(args[2], -1);
				if (replicas < 1 || replicas > 100) {
					throw new IllegalArgumentException("Bad number of replicas specified.");
				}

				uploadFile(args[1], replicas);
				break;
			case "download":
			case "down":
			case "d":
				if (args.length != 2) throw new IllegalArgumentException("Usage: download <file>");
				downloadFile(args[1]);
				break;
			case "remove":
			case "rm":
				if (args.length != 2) throw new IllegalArgumentException("Usage: remove <file>");
				removeFile(args[1]);
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

	@SuppressWarnings("SameParameterValue")
	private static int tryParseInt(String str, int fallback) {
		try {
			return Integer.parseInt(str);
		} catch (NumberFormatException ignore) {}
		return fallback;
	}

	public static void main(String[] args) {
		if (args.length != 1) {
			System.err.println("Usage: MWDFSClient <namenode>");
			System.exit(1);
		}

		new MWDFSClient(args[0]).shell();
	}
}

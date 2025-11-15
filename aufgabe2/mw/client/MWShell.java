package mw.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;


public abstract class MWShell {

	protected abstract boolean processCommand(String[] args) throws MWWebServiceException;

	public void shell() {
		// Create input reader and process commands
		BufferedReader commandLine = new BufferedReader(new InputStreamReader(System.in));
		while(true) {
			// Print prompt
			System.out.print("[MW]> ");
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
				long start = System.currentTimeMillis();
				boolean loop = processCommand(args);
				if(!loop) break;
				System.out.println("Execution took " + (System.currentTimeMillis() - start) + "ms");
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
		boolean quoted = false;
		boolean escaping = false;
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

}

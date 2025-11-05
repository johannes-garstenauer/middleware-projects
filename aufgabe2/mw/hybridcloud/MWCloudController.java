package mw.hybridcloud;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class MWCloudController {
	
	private MWCloudPlatform platform = null;
	private MWCloudPlatform aws = null;
	private MWCloudPlatform osc = null;
	
	public MWCloudController() {
		/*
		 * TODO: Implement constructor (e.g., initialize aws and osc)
		 */
	}
	
	private void startVM(String[] args) throws MWCloudException {
		/*
		 * TODO: Implement method
		 */
	}
	
	private void deleteVM(String[] args) throws MWCloudException {
		/*
		 * TODO: Implement method
		 */
	}
	
	private void listVMs(String[] args) throws MWCloudException {
		/*
		 * TODO: Implement method
		 */
	}
	
	private void getCPUUsage(String[] args) throws MWCloudException {
		/*
		 * TODO: Implement method (optional for 5.0 ECTS)
		 */
	}
	
	private void setPlatform(String[] args) {
		if(args.length < 2) throw new IllegalArgumentException("Usage: set-platform <platform (i.e., AWS|(OPENSTACK|OSC)>");
		
		String reqPlatform = args[1].toLowerCase();

		switch (reqPlatform) {
		case "osc":
		case "openstack":
			platform = osc;
			break;
		case "aws":
			platform = aws;
			break;
		default:
			throw new IllegalArgumentException("Invalid cloud platform (must be AWS or OSC)");
		}
	
		if (platform != null)
			System.out.println("Cloud platform \"" + reqPlatform  + "\" successfully set.");
	}
	
	public void shell() {
		// Create input reader and process commands
		BufferedReader commandLine = new BufferedReader(new InputStreamReader(System.in));
		while(true) {
			// Print prompt
			if (platform instanceof MWCloudPlatformOpenStack)
				System.out.print("[OSC]> ");
			else if (platform instanceof MWCloudPlatformAWS)
				System.out.print("[AWS]> ");
			else
				System.out.print("[?]> ");
			
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
			String[] args = command.split(" ");
			if(args.length == 0) continue;
			args[0] = args[0].toLowerCase();
			
			// Process command
			try {
				boolean loop = processCommand(args);
				if(!loop) break;
			} catch(IllegalArgumentException iae) {
				System.err.println(iae.getMessage());
			} catch(MWCloudException ce) {
				System.err.println("Cloud-platform error: " + ce.getMessage());
				ce.printStackTrace();
			}
		}
		
		// Close input reader
		try {
			commandLine.close();
		} catch(IOException ioe) {
			// Ignore
		}
	}

	private boolean processCommand(String[] args) throws MWCloudException {
		switch(args[0]) {
		case "help":
		case "h":
			System.out.println("The following commands are available:\n"
					+ "  help                     Print this text\n"
					+ "  set-platform <aws|osc>   Set active cloud platform, can either be aws or osc (OpenStack)\n"
					+ "  start-vm <arguments>     Start vm on the active platform with given arguments\n"
					+ "  delete-vm <vm>           Delete vm\n"
					+ "  list-vms                 List vms\n"
					+ "  get-cpu <vm> <timespan>  Query cpu usage of vm\n"
					+ "  quit                     Exit this program"
			);
			break;
		case "set-platform":
		case "sp":
			setPlatform(args);
			break;
		case "start-vm":
		case "start":
			startVM(args);
			break;
		case "delete-vm":
		case "delete-vms":
		case "delete":
		case "del":
			deleteVM(args);
			break;
		case "list-vms":
		case "list":
		case "lv":
		case "ls":
			listVMs(args);
			break;
		case "get-cpu":
		case "get-cpuu":
		case "get-cpuusage":
		case "gc":
			getCPUUsage(args);
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
	
	public static void main(String[] args) {
		MWCloudController cloudController = new MWCloudController();
		cloudController.shell();
	}
	
}

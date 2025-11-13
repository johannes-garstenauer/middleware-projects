package mw.hybridcloud;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

public class MWCloudController {
	
	private MWCloudPlatform platform = null;
	private MWCloudPlatform aws = null;
	private MWCloudPlatform osc = null;
	
	public MWCloudController() {
		this.aws = new MWCloudPlatformAWS();
	}

	// TODO blocking
	private void startVM(String[] args) throws MWCloudException {
		MWVirtualMachineConfig conf = new MWVirtualMachineConfig(
				"TestVM-from-MWCloudController3",
				"Amazon Linux 2 AMI",
				"ami-0b44ee2dcf07ee291", // Example AMI ID
				null,
				null,
				"gruppe01-new",
				"subnet-70560917",
				"sg-03a1e273a226a8b04",
				"gruppe01-new",
				"#!/bin/bash\n echo 'Hello from MWCloudController2' > /home/ec2-user/hello.txt"
		);
		MWVirtualMachine vm = aws.startVM(conf);
		System.out.println("Starting VM with ID: " + vm.vmId);

		// Block until VM is RUNNING
		int maxAttempts = 60;
		int attempt = 0;
		int sleepTimeSeconds = 3;

		while (attempt < maxAttempts) {
			if (aws.isInstanceRunning(vm)) {
				System.out.println("VM is now running.");
				return;
			}
			try {
				Thread.sleep(sleepTimeSeconds * 1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new MWCloudException("Thread was interrupted while waiting for VM to start.");
			}
			attempt++;
		}
		throw new MWCloudException("VM" + vm.vmId + "did not reach RUNNING state within the expected time.");
	}
	
	private void deleteVM(String[] args) throws MWCloudException {
		this.aws.deleteVM(new MWVirtualMachine(args[1], "", ""));
	}
	
	private void listVMs(String[] args) throws MWCloudException {
		List<MWVirtualMachine> vms = aws.listVMs();
		for (MWVirtualMachine vm : vms) {
			System.out.println("VM ID: " + vm.vmId + ", Name: " + vm.vmName + ", Status: " + vm.lastState);
		}
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
		//cloudController.shell();

		try {
			//cloudController.startVM(null);
			//cloudController.listVMs(null);
			//cloudController.startVM(null);
			cloudController.deleteVM(new String[]{"", "i-001f3e7aeb20bf176"});
			return;
		} catch (MWCloudException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


	}
	
}

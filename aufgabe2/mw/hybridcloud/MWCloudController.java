package mw.hybridcloud;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

/***
 * - Include in shell
 *
 * Adapt for both platforms
 * 1) startVM() with instanceRunnning (osc is already blocking!)
 *
 * Test authentication in CIP pool
 */
public class MWCloudController {

    private MWCloudPlatform platform = null;
    private MWCloudPlatform aws = null;
    private MWCloudPlatform osc = null;

    public MWCloudController() throws MWCloudException {
        this.aws = new MWCloudPlatformAWS();
        this.osc = new MWCloudPlatformOpenStack();
    }


    private void startVM(String[] args) throws MWCloudException {

        // Working configs for both platforms
        /***
        MWVirtualMachineConfig conf_aws = new MWVirtualMachineConfig(
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

        MWVirtualMachineConfig conf_osc = new MWVirtualMachineConfig(
                "TestVM-from-MWCloudController",
                "debian-example",
                "45d75974-9323-460f-8c84-6a83e0971f5f",
                "i4.tiny",
                "6920733b-7246-4cb0-bc76-75369006aba7",
                "internal",
                "722c8d94-101b-4cab-9910-8701e4d6533b",
                "4bb56afa-4a07-4f00-aaa7-ec723580be1e",
                "key-johannes",
                "#cloud-config\nruncmd:\n - echo 'Hello from MWCloudController' > /home/debian/hello.txt"
        );
        ***/

        MWVirtualMachineConfig config  = new MWVirtualMachineConfig(
                args[1],
                args[2],
                args[3],
                args[4],
                args[5],
                args[6],
                args[7],
                args[8],
                args[9],
                args[10]
        );

        MWVirtualMachine vm = platform.startVM(config);

        // OpenStack platform starts blocking by default, so we can return here
        if (platform instanceof MWCloudPlatformOpenStack) {
            System.out.println("Started: " + vm);
            return;
        }

        // Block until AWS VM is RUNNING
        int maxAttempts = 60;
        int attempt = 0;
        int sleepTimeSeconds = 3;

        while (attempt < maxAttempts) {
            if (aws.isInstanceRunning(vm)) {
                vm.lastState = "running";
                System.out.println("Started: " + vm);
                return;
            }
            try {
                Thread.sleep(sleepTimeSeconds * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MWCloudException("Thread was interrupted while waiting for VM " + vm + " to start.");
            }
            attempt++;
        }
        throw new MWCloudException("VM" + vm + "did not reach RUNNING state within the expected time.");
    }

    private void deleteVM(String[] args) throws MWCloudException {

        // Delete VM by ID only
        this.platform.deleteVM(new MWVirtualMachine(args[1], args[2], args[3]));
    }

    private void listVMs() throws MWCloudException {
        List<MWVirtualMachine> vms = platform.listVMs();
		for (MWVirtualMachine vm : vms) {
            System.out.println(vm);
        }
    }

    private void getCPUUsage(String[] args) throws MWCloudException {
        /*
         * TODO: Implement method (optional for 5.0 ECTS)
         */
    }

    private void setPlatform(String[] args) {
        if (args.length < 2)
            throw new IllegalArgumentException("Usage: set-platform <platform (i.e., AWS|(OPENSTACK|OSC)>");

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
            System.out.println("Cloud platform \"" + reqPlatform + "\" successfully set.");
    }

    public void shell() {
        // Create input reader and process commands
        BufferedReader commandLine = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
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
            } catch (IOException ioe) {
                break;
            }
            if (command == null) break;
            if (command.isEmpty()) continue;

            // Prepare command
            String[] args = command.split(" ");
            if (args.length == 0) continue;
            args[0] = args[0].toLowerCase();

            // Process command
            try {
                boolean loop = processCommand(args);
                if (!loop) break;
            } catch (IllegalArgumentException iae) {
                System.err.println(iae.getMessage());
            } catch (MWCloudException ce) {
                System.err.println("Cloud-platform error: " + ce.getMessage());
                ce.printStackTrace();
            }
        }

        // Close input reader
        try {
            commandLine.close();
        } catch (IOException ioe) {
            // Ignore
        }
    }

    private boolean processCommand(String[] args) throws MWCloudException {
        switch (args[0]) {
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
                listVMs();
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
        MWCloudController cloudController = null;
        try {
            cloudController = new MWCloudController();
        } catch (MWCloudException e) {
            throw new RuntimeException(e);
        }
        cloudController.shell();
    }
}

/*** DEMO COMMANDS
 *
 * 1)
 * sp aws
 * start-vm testAWSVM - ami-0b44ee2dcf07ee291 - - - subnet-70560917 sg-03a1e273a226a8b04 gruppe01-new example_data
 *
 * 2)
 * sp osc
 * start-vm testOSCVm debian-example 45d75974-9323-460f-8c84-6a83e0971f5f i4.tiny 6920733b-7246-4cb0-bc76-75369006aba7 internal 722c8d94-101b-4cab-9910-8701e4d6533b 4bb56afa-4a07-4f00-aaa7-ec723580be1e key-johannes example_data
 *
 *
 */

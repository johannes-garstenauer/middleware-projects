package mw.hybridcloud;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import mw.client.MWRegistryClient;
import mw.client.MWServiceInstanceManager;
import mw.client.MWWebServiceException;
import mw.hybridcloud.MWVirtualMachine.MWVirtualMachineProvider;

/***
 * Erweiterung der Klasse MWCloudController um das Anmelden und Abmelden von virtuellen Maschinen über
 * die I4-Registry
 *
 * Test authentication in CIP pool
 */
public class MWCloudController {

    private MWCloudPlatform platform = null;
    private MWCloudPlatform aws = null;
    private MWCloudPlatform osc = null;
    private MWServiceInstanceManager instanceManager;

    public MWCloudController() throws MWCloudException {
        this.aws = new MWCloudPlatformAWS();
        this.osc = new MWCloudPlatformOpenStack();
        instanceManager = new MWServiceInstanceManager(MWRegistryClient.readRegistryURL(), "gruppe1", "tweet");
        instanceManager.autoLogin();
    }

    /**
     * Bring the registry into the right state.
     *
     * This will avoid issues when this controller is force quit
     * and the state of the vms changed until the next start of the controller.
     * @throws MWCloudException
     */
    public void updateRegistryInstanceState() throws MWCloudException {
        Map<Boolean, List<MWVirtualMachine>> vms = Stream.concat(
            aws.listVMs().stream(),
            osc.listVMs().stream()
        ).collect(Collectors.partitioningBy(vm -> {
            try {
                return getCorrespondingPlatform(vm).isInstanceRunning(vm);
            } catch (MWCloudException e) {
                System.err.println("Could not check whether vm instance is running: " + vm.toString());
                e.printStackTrace();

                // even though we do not know the state of the vm
                // we return true here so we keep it in the registry
                return true;
            }
        }));

        // logging
        System.out.println("################## Offline VMs ##################");
        vms.get(false).stream().forEach(System.out::println);
        if (vms.get(false).isEmpty()) {
            System.out.println("[None]");
        }
        System.out.println("\n################## Online VMs ##################");
        vms.get(true).stream().forEach(System.out::println);
        if (vms.get(true).isEmpty()) {
            System.out.println("[None]");
        }
        System.out.println();

        // filter not earlier due to logging
        Function<List<MWVirtualMachine>, List<String>> transformVMs = list -> {
            return list
                .stream()
                .filter(vm -> vm.address != null && !vm.address.strip().isEmpty())
                .map(vm -> "http://" + vm.address + "/tweetservice")
                .toList();
        };

        List<String> offlineVMs = transformVMs.apply(vms.get(false));
        List<String> onlineVMs = transformVMs.apply(vms.get(true));

        List<String> urls;
        try {
            urls = instanceManager.listInstances(null);
        } catch (MWWebServiceException e) {
            throw new MWCloudException(e);
        }

        // delete all urls from registry of vms that do not appear to be online anymore
        urls.stream().filter(offlineVMs::contains).forEach(url -> {
            try {
                instanceManager.removeInstance(url);
            } catch (MWWebServiceException e) {
                System.err.println("Could not remove instance: " + e.getMessage());
            }
        });

        // add missing urls to registry of vms that were not yet added
        onlineVMs.stream().filter(url -> !urls.contains(url)).forEach(url -> {
            try {
                instanceManager.addInstance("http://" + url + "/tweetservice");
            } catch (MWWebServiceException e) {
                System.err.println("Could not add instance: " + e.getMessage());
            }
        });
    }

    private void startVM(String[] args) throws MWCloudException {

        // Working configs for both platforms
        /***
        MWVirtualMachineConfig conf_aws = new MWVirtualMachineConfig(
                "TestVM-from-MWCloudController3",
                "Amazon Linux 2 AMI",
                "ami-0b44ee2dcf07ee291",
                null,
                null,
                "gruppe01-new",
                "subnet-70560917",
                "sg-03a1e273a226a8b04",
                "gruppe01-new",
                "group=gruppe1-bucket;jar=mwtweetservice.jar;class=mw.hybridcloud.MWTweetService;parameters=http://0.0.0.0"
        );

        MWVirtualMachineConfig conf_osc = new MWVirtualMachineConfig(
                "TestVM-from-MWCloudController",
                "debian",
                "98b974a6-b89b-439d-b9b5-2283b509ecdb",
                "i4.tiny",
                "6920733b-7246-4cb0-bc76-75369006aba7",
                "internal",
                "722c8d94-101b-4cab-9910-8701e4d6533b",
                "4bb56afa-4a07-4f00-aaa7-ec723580be1e",
                "gruppe1",
                "group=gruppe1-bucket;jar=mwtweetservice.jar;class=mw.hybridcloud.MWTweetService;parameters=http://0.0.0.0"
        );
        ***/

        requireArgs(args, 11,
                "start-vm <vmMame> <imageName> <imageId> <flavorName> <flavorId> <networkName> <networkId> <securityGroup> <keyPair> <userData>"
        );

        MWVirtualMachineConfig config = new MWVirtualMachineConfig(
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

        // wait until instance is started
        if (platform instanceof MWCloudPlatformOpenStack) {
            // do nothing as the OpenStack platform starts blocking by default
        } else if (platform instanceof MWCloudPlatformAWS) {
            // wait until AWS VM is running
            int maxAttempts = 120;

            int attempt = 0;
            int sleepTimeSeconds = 3;

            System.out.print("Waiting for instance...");
            while (attempt < maxAttempts) {
                if (platform.isInstanceRunning(vm)) {
                    break;
                }

                try {
                    Thread.sleep(sleepTimeSeconds * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MWCloudException("Thread was interrupted while waiting for VM " + vm + " to start.");
                }
                System.out.print(".");
                attempt++;
            }

            // break line of waiting string
            System.out.println();

        } else {
            throw new IllegalStateException("Unknown platform");
        }

        vm.lastState = "running";

        System.out.println("Started: " + vm);

        // update vm state to get up-to-date address
        vm = getCorrespondingPlatform(vm).findVM(vm.vmId);
        if (vm.address != null && !vm.address.isEmpty()) {
            try {
                instanceManager.addInstance("http://" + vm.address + "/tweetservice");
                System.out.println("Added url " + vm.address + " to registry");
            } catch (MWWebServiceException e) {
                System.err.println("Could not add VM to registry: " + e.getMessage());
                System.err.println("Please try to restart this controller to automatically" +
                    " update the registry to the correct state");
                e.printStackTrace();
            }
        } else {
            System.err.println(String.format("VM  %s (%s) does not contain a public address!", vm.vmName, vm.vmId));
        }
    }

    private void deleteVM(String[] args) throws MWCloudException {
        requireArgs(args, 2,
                "delete-vm <vmId>"
        );

        String vmId = args[1];
        // MWVirtualMachineProvider provider = MWVirtualMachineProvider.fromString(args[2]);
        // if (provider == null) {
        //     System.out.println("Provider " + args[2] + " not found!");
        //     return;
        // }

        MWVirtualMachine machine = platform.findVM(vmId);
        if (machine == null) {
            System.out.println("Instance with id " + args[1] + " not found!");
            return;
        }

        platform.deleteVM(machine);
        if (machine.address != null && !machine.address.isEmpty()) {
            try {
                // may also throw an exception if the address was not part of the registry before
                instanceManager.removeInstance(machine.address);
                System.out.println("Removed url " + machine.address + " from registry");
            } catch (MWWebServiceException e) {
                System.err.println("Could not remove VM from registry: " + e.getMessage());
                System.err.println("Please try to restart this controller to automatically" +
                    " update the registry to the correct state");
                e.printStackTrace();
            }
        } else {
            System.err.println(String.format("VM  %s (%s) does not contain a public address!", machine.vmName, machine.vmId));
        }

        // Delete VM by ID only
        this.platform.deleteVM(new MWVirtualMachine(args[1], "", "", null));
    }

    private void listVMs() throws MWCloudException {
        List<MWVirtualMachine> vms = platform.listVMs();
        for (MWVirtualMachine vm : vms) {
            System.out.println(vm);
        }
    }

    private void requireArgs(String[] args, int expected, String usage) {
        if (args.length != expected) {
            System.out.println(
                    "Invalid number of arguments.\nUsage: " + usage
            );
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

    public MWCloudPlatform getCorrespondingPlatform(MWVirtualMachine vm) {
        return getCorrespondingPlatform(vm.provider);
    }

    public MWCloudPlatform getCorrespondingPlatform(MWVirtualMachineProvider provider) {
        return switch (provider) {
            case MWVirtualMachineProvider.OPENSTACK -> osc;
            case MWVirtualMachineProvider.AWS -> aws;
            default -> {
                throw new IllegalArgumentException("Provider not handled");
            }
        };
    }

    public static void main(String[] args) {
        MWCloudController cloudController = null;
        try {
            cloudController = new MWCloudController();
            cloudController.updateRegistryInstanceState();
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

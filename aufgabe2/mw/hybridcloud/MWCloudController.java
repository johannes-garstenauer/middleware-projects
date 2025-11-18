package mw.hybridcloud;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import mw.client.MWRegistryClient;
import mw.client.MWServiceInstanceManager;
import mw.client.MWWebServiceException;
import mw.hybridcloud.MWVirtualMachine.MWVirtualMachineProvider;
import mw.hybridcloud.MWCloudPlatformAWS;
import mw.hybridcloud.MWCloudPlatformOpenStack;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;

/***
 * Include in shell
 * einheitliches MWException Handling
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
    private MWServiceInstanceManager instanceManager;

    private static final int MAX_PRIVATE_INSTANCES = 2;
    private static final int MIN_PRIVATE_INSTANCES = 1;
    private static final double CPU_HIGH_THRESHOLD = 70.0;
    private static final double CPU_LOW_THRESHOLD  = 20.0;
    private static final int SCALE_UP_STABLE_CYCLES   = 3;
    private static final int SCALE_DOWN_STABLE_CYCLES = 5;
    private static final int MONITOR_INTERVAL_SECONDS        = 30;
    private static final int CPU_MEASUREMENT_WINDOW_SECONDS  = 60;
    private volatile boolean autoScalingEnabled = false;
    private int consecutiveHighLoadCycles = 0;
    private int consecutiveLowLoadCycles  = 0;

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
                instanceManager.addInstance(url);
            } catch (MWWebServiceException e) {
                System.err.println("Could not add instance: " + e.getMessage());
            }
        });
    }

    private MWVirtualMachine startTweetVM(MWVirtualMachine.MWVirtualMachineProvider provider) throws MWCloudException {
        MWVirtualMachineConfig conf;
        MWCloudPlatform targetPlatform;

        switch (provider) {
            case OPENSTACK -> {
                targetPlatform = osc;
                conf = new MWVirtualMachineConfig(
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
            }
            case AWS -> {
                targetPlatform = aws;
                conf = new MWVirtualMachineConfig(
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
            }
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        }

        MWVirtualMachine vm = targetPlatform.startVM(conf);
        System.out.println("Starting VM with ID: " + vm.vmId + " on provider " + provider);
        waitForRunningAndRegister(vm);
        return vm;
    }

    private void waitForRunningAndRegister(MWVirtualMachine vmInitial) throws MWCloudException {
        MWVirtualMachine vm = vmInitial;
        int maxAttempts = 120;
        int attempt = 0;
        int sleepTimeSeconds = 3;

        System.out.print("Waiting for instance...");
        while (attempt < maxAttempts) {
            if (getCorrespondingPlatform(vm).isInstanceRunning(vm)) {
                System.out.println();

                // update vm state to get up-to-date address
                vm = getCorrespondingPlatform(vm).findVM(vm.vmId);
                if (vm.address != null && !vm.address.isEmpty()) {
                    String url = "http://" + vm.address + "/tweetservice";
                    try {
                        instanceManager.addInstance(url);
                        System.out.println("Added url " + url + " to registry");
                    } catch (MWWebServiceException e) {
                        System.err.println("Could not add VM to registry: " + e.getMessage());
                        System.err.println("Please try to restart this controller to automatically" +
                                " update the registry to the correct state");
                        e.printStackTrace();
                    }
                } else {
                    System.err.println(String.format("VM  %s (%s) does not contain a public address!", vm.vmName, vm.vmId));
                }
                System.out.println("VM is now running.");
                return;
            }
            try {
                Thread.sleep(sleepTimeSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MWCloudException("Thread was interrupted while waiting for VM to start.");
            }
            System.out.print(".");
            attempt++;
        }

        System.out.println();
        throw new MWCloudException("VM " + vm.vmId + " did not reach RUNNING state within the expected time.");
    }

    
    // TODO blocking
    private void startVM(String[] args) throws MWCloudException {
        if (platform == null) {
            throw new IllegalStateException("No active cloud platform set. Use set-platform first.");
        }

        MWVirtualMachineProvider provider;
        if (platform instanceof MWCloudPlatformOpenStack) {
            provider = MWVirtualMachineProvider.OPENSTACK;
        } else if (platform instanceof MWCloudPlatformAWS) {
            provider = MWVirtualMachineProvider.AWS;
        } else {
            throw new IllegalStateException("Unknown active platform");
        }

        startTweetVM(provider);
    }


    private void terminateAndDeregister(MWVirtualMachine machine) throws MWCloudException {
        getCorrespondingPlatform(machine).deleteVM(machine);
        if (machine.address != null && !machine.address.isEmpty()) {
            String url = "http://" + machine.address + "/tweetservice";
            try {
                instanceManager.removeInstance(url);
                System.out.println("Removed url " + url + " from registry");
            } catch (MWWebServiceException e) {
                System.err.println("Could not remove VM from registry: " + e.getMessage());
                System.err.println("Please try to restart this controller to automatically" +
                        " update the registry to the correct state");
                e.printStackTrace();
            }
        } else {
            System.err.println(String.format("VM  %s (%s) does not contain a public address!", machine.vmName, machine.vmId));
        }
    }

    private void deleteVM(String[] args) throws MWCloudException {
        if (args.length != 3) {
            System.out.println("Usage: delete [vmId] [provider]");
            return;
        }

        String vmId = args[1];
        MWVirtualMachineProvider provider = MWVirtualMachineProvider.fromString(args[2]);
        if (provider == null) {
            System.out.println("Provider " + args[2] + " not found!");
            return;
        }

        MWVirtualMachine machine = getCorrespondingPlatform(provider).findVM(vmId);
        if (machine == null) {
            System.out.println("Instance with id " + args[1] + " not found!");
            return;
        }

        terminateAndDeregister(machine);
    }

    private void listVMs(String[] args) throws MWCloudException {
        //List<MWVirtualMachine> vms = aws.listVMs();
        List<MWVirtualMachine> vms = osc.listVMs();
		for (MWVirtualMachine vm : vms) {
            System.out.println("VM ID: " + vm.vmId + ", Name: " + vm.vmName + ", Status: " + vm.lastState);
        }
    }

    private void getCPUUsage(String[] args) throws MWCloudException {
        String vmId =  args[1];
        MWVirtualMachineProvider provider = MWVirtualMachineProvider.fromString(args[2]);
        int seconds = Integer.parseInt(args[3]);

        if (provider == null) {
            System.out.println("Provider " + args[2] + " not found!");
            return;
        }
        MWVirtualMachine machine = getCorrespondingPlatform(provider).findVM(vmId);
        if (machine == null) {
            System.out.println("Instance with id " + args[1] + " not found!");
            return;
        }
        Double cpuUsage = getCorrespondingPlatform(machine).getCPUUsage(machine, seconds);
        System.out.println("CPU usage for " + vmId + "in time interval " + seconds + ": " + cpuUsage);

    }

    public void startAutoScaling() {
        autoScalingEnabled = true;
        Thread t = new Thread(() -> {
            try {
                autoScalingLoop();
            } catch (MWCloudException e) {
                System.err.println("Auto-scaling stopped due to error: " + e.getMessage());
                e.printStackTrace();
            }
        }, "AutoScalingThread");

        t.setDaemon(true);
        t.start();
    }

    private void autoScalingLoop() throws MWCloudException {
        while (autoScalingEnabled) {
            performAutoScalingCycle();
            try {
                Thread.sleep(MONITOR_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void performAutoScalingCycle() throws MWCloudException {
        List<MWVirtualMachine> runningVMs = Stream.concat(
                aws.listVMs().stream(),
                osc.listVMs().stream()
        ).filter(vm -> {
            try {
                return getCorrespondingPlatform(vm).isInstanceRunning(vm);
            } catch (MWCloudException e) {
                System.err.println("Could not determine state of VM " + vm.vmId + ": " + e.getMessage());
                return false;
            }
        }).toList();

        if (runningVMs.isEmpty()) {
            System.err.println("No running instances found. Starting fallback private instance...");
            startTweetVM(MWVirtualMachineProvider.OPENSTACK);
            consecutiveHighLoadCycles = 0;
            consecutiveLowLoadCycles = 0;
            return;
        }

        long privateCount = runningVMs.stream()
                .filter(vm -> vm.provider == MWVirtualMachineProvider.OPENSTACK)
                .count();

        if (privateCount == 0) {
            System.err.println("No private instances running. Starting one...");
            startTweetVM(MWVirtualMachineProvider.OPENSTACK);
            return;
        }

        double sumCpu = 0.0;
        int count = 0;
        for (MWVirtualMachine vm : runningVMs) {
            Double cpu = getCorrespondingPlatform(vm).getCPUUsage(vm, CPU_MEASUREMENT_WINDOW_SECONDS);
            if (cpu != null) {
                sumCpu += cpu;
                count++;
            }
        }

        if (count == 0) {
            System.err.println("Could not retrieve CPU usage for any VM.");
            return;
        }

        double avgCpu = sumCpu / count;
        System.out.println("Average CPU usage (last " + CPU_MEASUREMENT_WINDOW_SECONDS + "s): " + avgCpu + "%");

        if (avgCpu > CPU_HIGH_THRESHOLD) {
            consecutiveHighLoadCycles++;
            consecutiveLowLoadCycles = 0;
            System.out.println("High-load cycle " + consecutiveHighLoadCycles + "/" + SCALE_UP_STABLE_CYCLES);

            if (consecutiveHighLoadCycles >= SCALE_UP_STABLE_CYCLES) {
                scaleOut();
                consecutiveHighLoadCycles = 0;
            }
        } else if (avgCpu < CPU_LOW_THRESHOLD) {
            consecutiveLowLoadCycles++;
            consecutiveHighLoadCycles = 0;
            System.out.println("Low-load cycle " + consecutiveLowLoadCycles + "/" + SCALE_DOWN_STABLE_CYCLES);

            if (consecutiveLowLoadCycles >= SCALE_DOWN_STABLE_CYCLES) {
                scaleIn(runningVMs);
                consecutiveLowLoadCycles = 0;
            }
        } else {
            consecutiveHighLoadCycles = 0;
            consecutiveLowLoadCycles = 0;
        }
    }

    private void scaleOut() throws MWCloudException {
        List<MWVirtualMachine> privateVMs = osc.listVMs().stream()
                .filter(vm -> {
                    try {
                        return getCorrespondingPlatform(vm).isInstanceRunning(vm);
                    } catch (MWCloudException e) {
                        return false;
                    }
                }).toList();

        int privateCount = privateVMs.size();

        if (privateCount < MAX_PRIVATE_INSTANCES) {
            System.out.println("Scaling OUT in private cloud (OpenStack)...");
            startTweetVM(MWVirtualMachineProvider.OPENSTACK);
        } else {
            System.out.println("Scaling OUT in public cloud (AWS)...");
            startTweetVM(MWVirtualMachineProvider.AWS);
        }
    }

    private void scaleIn(List<MWVirtualMachine> runningVMs) throws MWCloudException {
        List<MWVirtualMachine> privateVMs = runningVMs.stream()
                .filter(vm -> vm.provider == MWVirtualMachineProvider.OPENSTACK)
                .toList();

        List<MWVirtualMachine> publicVMs = runningVMs.stream()
                .filter(vm -> vm.provider == MWVirtualMachineProvider.AWS)
                .toList();

        if (!publicVMs.isEmpty()) {
            MWVirtualMachine victim = publicVMs.get(publicVMs.size() - 1);
            System.out.println("Scaling IN: terminating PUBLIC instance " + victim.vmId);
            terminateAndDeregister(victim);
        } else if (privateVMs.size() > MIN_PRIVATE_INSTANCES) {
            MWVirtualMachine victim = privateVMs.get(privateVMs.size() - 1);
            System.out.println("Scaling IN: terminating PRIVATE instance " + victim.vmId);
            terminateAndDeregister(victim);
        } else {
            System.out.println("Scale-in skipped: already at minimum number of private instances.");
        }
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
        try {
            MWCloudController cloudController = new MWCloudController();

            System.out.println("Initializing Service Registry...");
            cloudController.updateRegistryInstanceState();
            cloudController.startTweetVM(MWVirtualMachine.MWVirtualMachineProvider.OPENSTACK);
            cloudController.startAutoScaling();
            cloudController.shell();

			//cloudController.startVM(null);
            //cloudController.listVMs(null);
            //cloudController.startVM(null);
            // cloudController.deleteVM(new String[]{"", "i-0f1cc3ff61659f976", "aws"});
			//cloudController.deleteVM(new String[]{"", "c6bc906a-3f28-4e12-a098-2d2b8ee8b1ec", "os"});
            //throw new MWCloudException("");

            // TODO enable shell
            // cloudController.shell();
        } catch (MWCloudException e) {
            e.printStackTrace();
            return;
        }
        //cloudController.shell();
    }

}

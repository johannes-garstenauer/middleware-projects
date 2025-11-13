package mw.hybridcloud;

import java.util.Base64;
import java.util.List;
import java.util.ArrayList;

// import aws sdk from
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

/***
 * TODO:
 * 1) start VM in cli
 * 2) transfer to code
 * 3) test functions in controller
 */

/***
 * CLI commands:
 * 1) aws ec2 describe-subnets | grep -i subnetid
 * 2) aws ec2 describe-security-groups --filters Name=group-name,Values=i4mw \
 * | grep -i -e groupname -e groupid
 * 3) aws ec2 describe-key-pairs | grep -i -e keyname -e keypairid
 *
 * 4) aws ec2 run-instances --instance-type t2.nano \
 *          --image-id ami-0d4ecc2431e0ef9e1 \
 *          --key gruppe01-new --user-data="Hello World" \
 *          --subnet-id subnet-70560917 \
 *          --security-group-ids sg-03a1e273a226a8b04
 *
 * 5) ssh -i ~/.aws/gruppe01-new.pem ec2-user@ec2-54-74-49-196.eu-west-1.compute.amazonaws.com # TODO replace with whichever appropriate
 *
 *
 */
public class MWCloudPlatformAWS implements MWCloudPlatform {
    private Ec2Client ec2;
    public static final String INSTANCE_TYPE = "t2.nano";

    public MWCloudPlatformAWS() throws MWCloudException {

        try {
            this.ec2 = Ec2Client.builder()
                    .region(Region.EU_WEST_1)
                    .build();
        } catch (Exception e) {
            throw new MWCloudException("Failed to create AWS EC2 client: " + e.getMessage(), e);
        }
    }

    @Override
    public MWVirtualMachine startVM(MWVirtualMachineConfig conf) throws MWCloudException {
        try {
            Tag tag = Tag.builder().key("Name").value(conf.vmName).build();
            TagSpecification spec = TagSpecification.builder()
                    .tags(tag)
                    .resourceType(ResourceType.INSTANCE)
                    .build();

            byte[] userDataBytes = (conf.userData != null ? conf.userData : "Hello World").getBytes();

            RunInstancesRequest request = RunInstancesRequest.builder()
                    .imageId(conf.imageId)
                    .tagSpecifications(spec)
                    .instanceType(INSTANCE_TYPE)
                    .minCount(1)
                    .maxCount(1)
                    .keyName(conf.keyName)
                    .userData(Base64.getEncoder().encodeToString(userDataBytes))
                    .monitoring(RunInstancesMonitoringEnabled.builder().enabled(true).build())
                    .securityGroupIds(conf.securityGroup) // z.B. im Web-Interface erstellen
                    .subnetId(conf.networkId) // (VPC muss Security-Group vorab zugeordnet werden)
                    .build();
            ;

            RunInstancesResponse response = ec2.runInstances(request);

            if (response == null || response.instances() == null || response.instances().isEmpty()) {
                throw new MWCloudException("No instances were created");
            }

            Instance inst = response.instances().getFirst();
            String id = inst.instanceId();
            String name = inst.tags() == null ? "" :
                    inst.tags().stream()
                            .filter(t -> "Name".equals(t.key()))
                            .map(Tag::value)
                            .findFirst()
                            .orElse("");
            String address = inst.publicIpAddress() != null ? inst.publicIpAddress() : inst.privateIpAddress();

            MWVirtualMachine vm = new MWVirtualMachine(id, name, address);
            if (inst.state() != null && inst.state().nameAsString() != null) {
                vm.lastState = inst.state().nameAsString();
            }

            return vm;
        } catch (Ec2Exception e) {
            throw new MWCloudException("AWS EC2 error: " + (e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage()), e);
        } catch (Exception e) {
            throw new MWCloudException(e.getMessage(), e);
        }
    }

    public boolean isInstanceRunning(MWVirtualMachine vm) throws MWCloudException {
        try {
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .instanceIds(vm.vmId)
                    .build();

            DescribeInstancesResponse response = ec2.describeInstances(request);

            for (Reservation reservation : response.reservations()) {
                for (Instance instance : reservation.instances()) {
                    InstanceStateName state = instance.state().name();
                    System.out.println("Instance state: " + state);
                    return state == InstanceStateName.RUNNING;
                }
            }
            return false;
        } catch (Ec2Exception e) {
            throw new MWCloudException("AWS EC2 error: " + (e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage()), e);
        } catch (Exception e) {
            throw new MWCloudException(e.getMessage(), e);
        }
    }

    @Override
    public void deleteVM(MWVirtualMachine vm_ref) throws MWCloudException {
        try {
            TerminateInstancesRequest request = TerminateInstancesRequest.builder()
                    .instanceIds(vm_ref.vmId)
                    .build();
            TerminateInstancesResponse response = ec2.terminateInstances(request);

            if (response == null || !response.hasTerminatingInstances()) {
                throw new MWCloudException("No instances were terminated!");
            }

            for (InstanceStateChange terminated_instance : response.terminatingInstances()) {
                System.out.println("Terminated instance ID: " + terminated_instance.instanceId() +
                        ", previous state: " + terminated_instance.previousState().nameAsString() +
                        ", current state: " + terminated_instance.currentState().nameAsString());
            }
        } catch (Ec2Exception e) {
            throw new MWCloudException("AWS EC2 error: " + (e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage()), e);
        } catch (Exception e) {
            throw new MWCloudException(e.getMessage(), e);
        }
    }


    @Override
    public List<MWVirtualMachine> listVMs() throws MWCloudException {
        try {
            DescribeInstancesRequest req = DescribeInstancesRequest.builder().build();
            DescribeInstancesResponse resp = ec2.describeInstances(req);

            List<MWVirtualMachine> vms = new ArrayList<>();
            for (Reservation reservation : resp.reservations()) {
                for (Instance inst : reservation.instances()) {
                    String id = inst.instanceId();
                    String name = inst.tags().stream()
                            .filter(t -> "Name".equals(t.key()))
                            .map(t -> t.value())
                            .findFirst()
                            .orElse("");
                    String address = inst.publicIpAddress() != null ? inst.publicIpAddress() : inst.privateIpAddress();
                    MWVirtualMachine vm = new MWVirtualMachine(id, name, address);
                    if (inst.state() != null && inst.state().nameAsString() != null) {
                        vm.lastState = inst.state().nameAsString();
                    }
                    vms.add(vm);
                }
            }
            return vms;
        } catch (Ec2Exception e) {
            throw new MWCloudException("AWS EC2 error: " + (e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage()), e);
        } catch (Exception e) {
            throw new MWCloudException(e.getMessage(), e);
        }
    }

    @Override
    public Double getCPUUsage(MWVirtualMachine vm, int seconds) throws MWCloudException {
        /*
         *  TODO: Implement method (optional for 5.0 ECTS)
         */
        return null;
    }

}

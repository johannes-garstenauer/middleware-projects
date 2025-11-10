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

public class MWCloudPlatformAWS implements MWCloudPlatform {
    private Ec2Client ec2;
    private static final String IMAGE_ID = "ami-0b44ee2dcf07ee291";
    public static final String INSTANCE_TYPE = "t2.nano";
    public static final String GROUP_KEY = "gruppe01-key";

    public MWCloudPlatformAWS() {
        this.ec2 = Ec2Client.builder()
                .region(Region.EU_WEST_1)
                .build();
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
                    .userData(Base64.getEncoder().encodeToString(userDataBytes)) //TODO: what to add here?
                    .monitoring(RunInstancesMonitoringEnabled.builder().enabled(true).build())
                    .securityGroupIds(conf.securityGroup) // z.B. im Web-Interface erstellen
                    .subnetId(conf.networkId) // (VPC muss Security-Group vorab zugeordnet werden)
                    .build();;

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

    @Override
    public void deleteVM(MWVirtualMachine vm_ref) throws MWCloudException {
        /*
         *  TODO: Implement method
         */
        return;
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

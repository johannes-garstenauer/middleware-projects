package mw.hybridcloud;

import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.model.common.Identifier;
import org.openstack4j.model.compute.Action;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.ServerCreate;
import org.openstack4j.openstack.OSFactory;

import java.util.List;


public class MWCloudPlatformOpenStack implements MWCloudPlatform {

    private final OSClient.OSClientV3 client;


    public MWCloudPlatformOpenStack() throws MWCloudException {
        String user = System.getenv("OS_USERNAME");
        String pass = System.getenv("OS_PASSWORD");
        String authUrl = System.getenv("OS_AUTH_URL");
        String projectId = System.getenv("OS_PROJECT_ID");
        String domainName = System.getenv("OS_USER_DOMAIN_NAME");

        if (user == null || pass == null || authUrl == null || projectId == null || domainName == null) {
            throw new MWCloudException("One or more required OpenStack environment variables are not set:" +
                    "\n " +
                    "OS_USERNAME, OS_PASSWORD, OS_AUTH_URL, OS_PROJECT_ID, and OS_USER_DOMAIN_NAME.");
        }

        Identifier userDomainName = Identifier.byName(domainName);
        Identifier projectIdentifier = Identifier.byId(projectId);

        try {
            this.client = OSFactory.builderV3() // Packages:
                    .endpoint(authUrl) // org.openstack4j.{api,openstack}
                    .credentials(user, pass, userDomainName)
                    .scopeToProject(projectIdentifier)
                    .authenticate();
        } catch (Exception e) {
            throw new MWCloudException("Failed to authenticate with OpenStack: " + e.getMessage(), e);
        }
    }

    @Override
    public MWVirtualMachine startVM(MWVirtualMachineConfig conf) throws MWCloudException {
        ServerCreate sc = Builders.server()
                .name(conf.vmName)
                .userData(conf.userData)
                .flavor(conf.flavorId)
                .image(conf.imageId)
                .keypairName(conf.keyName)
                .networks(List.of(conf.networkId))
                .addSecurityGroup(conf.securityGroup)
                .userData(conf.userData)
                .build();

        try {
            Server server = client.compute().servers()
                    .bootAndWaitActive(sc, 60000); // 1 min. max wait-time

            return new MWVirtualMachine(
                    server.getId(),
                    server.getName(),
                    server.getAccessIPv4() != null ? server.getAccessIPv4() : server.getAccessIPv6()
            );
        } catch (Exception e) {
            throw new MWCloudException("Failed to start VM: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteVM(MWVirtualMachine vm_ref) throws MWCloudException {
        try {
            client.compute().servers().action(vm_ref.vmId, Action.SUSPEND);
            client.compute().servers().delete(vm_ref.vmId);
            System.out.println("Deleted VM: " + vm_ref);
        } catch (Exception e) {
            throw new MWCloudException("Failed to delete VM:" + vm_ref + e.getMessage(), e);
        }
    }

    @Override
    public List<MWVirtualMachine> listVMs() throws MWCloudException {
        try {
            List<? extends Server> servers = client.compute().servers().list();
            return servers.stream().map(server -> new MWVirtualMachine(
                    server.getId(),
                    server.getName(),
                    server.getAccessIPv4() != null ? server.getAccessIPv4() : server.getAccessIPv6()
            )).toList();
        } catch (Exception e) {
            throw new MWCloudException("Failed to list VMs: " + e.getMessage(), e);
        }
    }

    @Override
    public Double getCPUUsage(MWVirtualMachine vm, int seconds) throws MWCloudException {
        /*
         *  TODO: Implement method (optional for 5.0 ECTS)
         */
        return null;
    }

    @Override
    public boolean isInstanceRunning(MWVirtualMachine vm) throws MWCloudException {
        // Check server status
        Server server = client.compute().servers().get(vm.vmId);
        if (server == null) {
            throw new MWCloudException("No server found with ID: " + vm.vmId);
        }
        return server.getStatus() == Server.Status.ACTIVE;
    }
}

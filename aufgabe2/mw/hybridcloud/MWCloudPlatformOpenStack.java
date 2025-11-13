package mw.hybridcloud;

import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.model.common.Identifier;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.ServerCreate;
import org.openstack4j.openstack.OSFactory;

import java.util.List;


public class MWCloudPlatformOpenStack implements MWCloudPlatform {

    /***
     * debian-example
     * i4.tiny
     */

    private OSClient.OSClientV3 client;


    public MWCloudPlatformOpenStack() throws MWCloudException {
        String user = System.getenv("OS_USERNAME");
        String pass = System.getenv("OS_PASSWORD");
        String authUrl = System.getenv("OS_AUTH_URL");
        String projectId = System.getenv("OS_PROJECT_ID");
        String domainName = System.getenv("OS_USER_DOMAIN_NAME");
        try {
            if (user == null || pass == null || authUrl == null || projectId == null || domainName == null) {
                /***
                 *System.out.printf("User: %s\n", user);
                 *System.out.printf("Pass: %s\n", pass);
                 *System.out.printf("Auth URL: %s\n", authUrl);
                 *System.out.printf("Project ID: %s\n", projectId);
                 * System.out.printf("Domain Name: %s\n", domainName);
                 ***/
                throw new IllegalArgumentException("One or more required OpenStack environment variables are not set.");
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Please ensure OS_USERNAME, OS_PASSWORD, OS_AUTH_URL, OS_PROJECT_ID, and OS_USER_DOMAIN_NAME are set.");
            throw e;
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

        for (Flavor flavor :client.compute().flavors().list()) {
            System.out.println("Flavor: " + flavor.getName() + " | ID: " + flavor.getId());
        }
        ServerCreate sc = Builders.server() // org.openstack4j.{model.compute,api}
                .name(conf.vmName)
                .userData(conf.userData)
                .flavor(conf.flavorId) //TODO: what about as Flavor object? flavorName vs flavorId?
                .image(conf.imageId) //TODO: what about as Image object? imageName vs imageId?
                .keypairName(conf.keyName) //TODO: hardcoded
                .networks(List.of(conf.networkId))
                .addSecurityGroup(conf.securityGroup)
                .userData(conf.userData)
                .build();

        Server server = client.compute().servers()
                .bootAndWaitActive(sc, 60000); // 1 min wait-time

        return new MWVirtualMachine(
                server.getId(),
                server.getName(),
                server.getAccessIPv4() != null ? server.getAccessIPv4() : server.getAccessIPv6()
                );
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
        /*
         *  TODO: Implement method
         */
        return null;
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

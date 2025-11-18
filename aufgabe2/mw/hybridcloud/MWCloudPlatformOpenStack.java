package mw.hybridcloud;

import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.common.Identifier;
import org.openstack4j.model.compute.Action;
import org.openstack4j.model.compute.Address;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.ServerCreate;
import org.openstack4j.openstack.OSFactory;
import org.openstack4j.openstack.compute.domain.NovaFloatingIP;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import mw.hybridcloud.MWVirtualMachine.MWVirtualMachineProvider;
import mw.hybridcloud.MWGnocchiInstanceResource;

import java.util.Arrays;
import java.util.List;


public class MWCloudPlatformOpenStack implements MWCloudPlatform {


    private final OSClient.OSClientV3 client;
    private final Client httpClient;
    private final WebTarget gnocchiBase;
    private final String authToken;


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
        this.authToken = client.getToken().getId();
        this.httpClient = ClientBuilder.newClient();
        String metricURL = "https://i4cloud1.informatik.uni-erlangen.de:8041 ";
        this.gnocchiBase = httpClient.target(metricURL).path("v1");

    }

    @Override
    public MWVirtualMachine startVM(MWVirtualMachineConfig conf) throws MWCloudException {
        byte[] userDataBase64 = conf.userData != null ? conf.userData.getBytes() : null;

        for (Flavor flavor :client.compute().flavors().list()) {
            System.out.println("Flavor: " + flavor.getName() + " | ID: " + flavor.getId());
        }
        ServerCreate sc = Builders.server()
                .name(conf.vmName)
                .userData(conf.userData)
                .flavor(conf.flavorId)
                .image(conf.imageId)
                .keypairName(conf.keyName)
                .networks(List.of(conf.networkId))
                .addSecurityGroup(conf.securityGroup)
                .userData(Arrays.toString(userDataBase64))
                .build();

        try {
            Server server = client.compute().servers()
                    .bootAndWaitActive(sc, 60000); // 1 min. max wait-time

            NovaFloatingIP floatingIp = (NovaFloatingIP) client.compute().floatingIps().list().stream()
                    .filter(floatingIP -> floatingIP.getInstanceId() == null)
                    .findFirst()
                    .orElseThrow(() -> new MWCloudException("No available floating IPs found"));

            ActionResponse r = client.compute().floatingIps().addFloatingIP(server, floatingIp.getFloatingIpAddress());
            if (!r.isSuccess()) {
                throw new MWCloudException("Failed to associate floating IP: " + r.getFault());
            }

            MWVirtualMachine vm = new MWVirtualMachine(
                    server.getId(),
                    server.getName(),
                    floatingIp.getFloatingIpAddress(),
                    MWVirtualMachineProvider.OPENSTACK
            );
            vm.lastState = server.getStatus().name();
            return vm;

        } catch (Exception e) {
            throw new MWCloudException("Failed to start VM: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteVM(MWVirtualMachine vm_ref) throws MWCloudException {
        try {
            client.compute().servers().action(vm_ref.vmId, Action.SUSPEND);
            client.compute().servers().delete(vm_ref.vmId);
            System.out.println("Deleted VM: " + vm_ref.vmId);
        } catch (Exception e) {
            throw new MWCloudException("Failed to delete VM:" + vm_ref + e.getMessage(), e);
        }
    }

    @Override
    public List<MWVirtualMachine> listVMs() throws MWCloudException {
        try {
            List<? extends Server> servers = client.compute().servers().list();

            List<MWVirtualMachine> vms = servers.stream().map(this::convertVirtualMachine).toList();

            vms.forEach(vm -> vm.lastState = client.compute().servers().get(vm.vmId).getStatus().name());
            return vms;
        } catch (Exception e) {
            throw new MWCloudException("Failed to list VMs: " + e.getMessage(), e);
        }
    }

    @Override
    public MWVirtualMachine findVM(String id) throws MWCloudException {
        try {
            Server server = client.compute().servers().get(id);
            return server == null ? null : convertVirtualMachine(server);
        } catch (Exception e) {
            throw new MWCloudException("Failed to list VMs: " + e.getMessage(), e);
        }
    }

    private MWVirtualMachine convertVirtualMachine(Server server) {
        return new MWVirtualMachine(
                server.getId(),
                server.getName(),
                server.getAddresses().getAddresses("internal").stream()
                        .filter(addr -> addr.getType().equals("floating") && addr.getVersion() == 4)
                        .map(Address::getAddr)
                        .findFirst()
                        .orElse(server.getAddresses().getAddresses("internal").getFirst().getAddr()),
                MWVirtualMachineProvider.OPENSTACK
        );
    }

    @Override
    public Double getCPUUsage(MWVirtualMachine vm, int seconds) throws MWCloudException {
        try {
            WebTarget resTarget = gnocchiBase.path("resources").path("instance").path(vm.vmId);
            MWGnocchiInstanceResource resource = resTarget
                    .request(MediaType.APPLICATION_JSON)
                    .header("X-Auth-Token", authToken)
                    .get(MWGnocchiInstanceResource.class);
            MWGnocchiInstanceResource gnocciResource = resTarget.request(MediaType.APPLICATION_JSON)
                    .header("X-Auth-Token", authToken)
                    .get(MWGnocchiInstanceResource.class);
            if (gnocciResource == null || gnocciResource.getMetrics() == null) {
                throw new MWCloudException("No Gnocchi resource/metrics found for VM " + vm.vmId);
            }
            String cpuMetricId = resource.getMetrics().get("cpu");
            if (cpuMetricId == null || cpuMetricId.isEmpty()) {
                throw new MWCloudException("No CPU metric found for VM " + vm.vmId);
            }
            //granularity is different to aws because here the 10s interval is free
            WebTarget measuresTarget = gnocchiBase
                    .path("metric")
                    .path(cpuMetricId)
                    .path("measures")
                    .queryParam("start", "-" + seconds + "seconds")
                    .queryParam("granularity", 10)
                    .queryParam("aggregation", "rate:mean");
            Response response = measuresTarget
                    .request(MediaType.APPLICATION_JSON)
                    .header("X-Auth-Token", authToken)
                    .get();
            if (response.getStatus() != 200) {
                String body = response.readEntity(String.class);
                throw new MWCloudException("Gnocchi measures request failed: HTTP "
                        + response.getStatus() + " - " + body);
            }
            String[][] measures = response.readEntity(String[][].class);
            if (measures == null) {
                return null;
            }
            double sumUtil = 0.0;
            int count = 0;

            for (String[] m : measures) {
                if (m == null || m.length < 3) {
                    continue;
                }
                double periodSec;
                double valueNs;
                periodSec = Double.parseDouble(m[1]);
                valueNs = Double.parseDouble(m[2]);
                double periodNs = periodSec * 1_000_000_000d;
                double utilizationPercent = (valueNs / periodNs) * 100.0;
                sumUtil += utilizationPercent;
                count++;
            }
            if (count == 0) {
                return 0.0;
            }
            return sumUtil / count;
        } catch (MWCloudException e) {
            throw e;
        } catch (Exception e) {
            throw new MWCloudException("Failed to get OpenStack/Gnocchi CPU usage: " + e.getMessage(), e);
        }
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

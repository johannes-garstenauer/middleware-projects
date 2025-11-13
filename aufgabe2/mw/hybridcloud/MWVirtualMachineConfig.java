package mw.hybridcloud;

public class MWVirtualMachineConfig {

    public String vmName;

    public String imageName;
    public String imageId;

    public String flavorName;
    public String flavorId;

    public String networkName;
    public String networkId;

    public String securityGroup;
    public String keyName;

    public String userData;

    public MWVirtualMachineConfig(String vmName, String imageName, String imageId, String flavorName, String flavorId,
                                  String networkName, String networkId, String securityGroup, String keyName, String userData) {
        this.vmName = vmName;
        this.imageName = imageName;
        this.imageId = imageId;
        this.flavorName = flavorName;
        this.flavorId = flavorId;
        this.networkName = networkName;
        this.networkId = networkId;
        this.securityGroup = securityGroup;
        this.keyName = keyName;
        this.userData = userData;
    }
}
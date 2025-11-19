package mw.hybridcloud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MWGnocchiInstanceResource {
    private String created_by_user_id;
    private String started_at;
    private String revision_start;
    private String revision_end;
    private String ended_at;
    private String creator;
    private String created_by_project_id;
    private String host;
    private String image_ref;
    private String flavor_id;
    private String flavor_name;
    private String server_group;
    private String original_resource_id;
    private String user_id;
    private String project_id;
    private String type;
    private String id;
    private String display_name;
    // Map from metric-name to metric-id
    private Map<String, String> metrics;

    public MWGnocchiInstanceResource() {}

    public String getCreated_by_user_id() {
        return created_by_user_id;
    }

    public void setCreated_by_user_id(String created_by_user_id) {
        this.created_by_user_id = created_by_user_id;
    }

    public String getStarted_at() {
        return started_at;
    }

    public void setStarted_at(String started_at) {
        this.started_at = started_at;
    }

    public String getRevision_start() {
        return revision_start;
    }

    public void setRevision_start(String revision_start) {
        this.revision_start = revision_start;
    }

    public String getRevision_end() {
        return revision_end;
    }

    public void setRevision_end(String revision_end) {
        this.revision_end = revision_end;
    }

    public String getEnded_at() {
        return ended_at;
    }

    public void setEnded_at(String ended_at) {
        this.ended_at = ended_at;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public String getCreated_by_project_id() {
        return created_by_project_id;
    }

    public void setCreated_by_project_id(String created_by_project_id) {
        this.created_by_project_id = created_by_project_id;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getImage_ref() {
        return image_ref;
    }

    public void setImage_ref(String image_ref) {
        this.image_ref = image_ref;
    }

    public String getFlavor_id() {
        return flavor_id;
    }

    public void setFlavor_id(String flavor_id) {
        this.flavor_id = flavor_id;
    }
    
    public String getFlavor_name() {
        return flavor_name;
    }

    public void setFlavor_name(String flavor_name) {
        this.flavor_name = flavor_name;
    }

    public String getServer_group() {
        return server_group;
    }

    public void setServer_group(String server_group) {
        this.server_group = server_group;
    }

    public String getOriginal_resource_id() {
        return original_resource_id;
    }

    public void setOriginal_resource_id(String original_resource_id) {
        this.original_resource_id = original_resource_id;
    }

    public String getUser_id() {
        return user_id;
    }

    public void setUser_id(String user_id) {
        this.user_id = user_id;
    }

    public String getProject_id() {
        return project_id;
    }

    public void setProject_id(String project_id) {
        this.project_id = project_id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplay_name() {
        return display_name;
    }

    public void setDisplay_name(String display_name) {
        this.display_name = display_name;
    }

    public Map<String, String> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, String> metrics) {
        this.metrics = metrics;
    }
}

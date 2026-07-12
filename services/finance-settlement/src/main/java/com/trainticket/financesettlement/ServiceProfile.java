package com.trainticket.financesettlement;

public record ServiceProfile(
    String serviceId,
    String domain,
    String language,
    String phase,
    String workPackages,
    String owns
) {
    public String health() {
        return "ok";
    }
}

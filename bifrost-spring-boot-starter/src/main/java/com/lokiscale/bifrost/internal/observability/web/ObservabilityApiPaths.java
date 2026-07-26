package com.lokiscale.bifrost.internal.observability.web;

public final class ObservabilityApiPaths
{
    public static final String ROOT = "/_bifrost/observability/v1";
    public static final String INSTANCE = ROOT + "/instance";
    public static final String SKILLS = ROOT + "/skills";
    public static final String ACTIVE = ROOT + "/active-executions";
    public static final String TRACES = ROOT + "/traces";

    private ObservabilityApiPaths() {}
}

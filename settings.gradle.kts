rootProject.name = "tracknyc"

// live-core: the reusable real-time foundation. Each sub-module is an independent
// java-library so it can be extracted into a shared library later without changes.
include(":live-core:geo")
include(":live-core:feed")
include(":live-core:state")
include(":live-core:push")

// The TrackNYC application (train adapters, position engine, REST + SSE API).
include(":tracknyc-service")

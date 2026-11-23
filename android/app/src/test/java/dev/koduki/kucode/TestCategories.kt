package dev.koduki.kucode

/**
 * JUnit category marker for medium tests that exercise real OS subprocesses,
 * loopback TCP sockets, or bundled multi-megabyte runtime archives.
 *
 * Fast small unit tests run via `:app:testDebugUnitTest`; medium tests run via
 * `:app:testDebugMediumUnitTest` (and both run as part of `:app:check`).
 */
interface MediumTest

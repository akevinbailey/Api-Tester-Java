//
// Created by A. Kevin Bailey on 8/10/2024 under a GPL3.0 license
//
package org.akb;

import javax.net.ssl.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ApiTester {
    // Implementation of the string HTTP status codes, because the HttpClient class does not have them.
    private static final Map<Integer, String> statusCodeMap = new HashMap<>();
    static {
        // Initialize the map with common HTTP status codes and phrases
        statusCodeMap.put(200, "OK");
        statusCodeMap.put(201, "Created");
        statusCodeMap.put(202, "Accepted");
        statusCodeMap.put(204, "No Content");
        statusCodeMap.put(205, "Reset Content");
        statusCodeMap.put(206, "Partial Content");
        statusCodeMap.put(400, "Bad Request");
        statusCodeMap.put(401, "Unauthorized");
        statusCodeMap.put(403, "Forbidden");
        statusCodeMap.put(404, "Not Found");
        statusCodeMap.put(405, "Method Not Allowed");
        statusCodeMap.put(406, "Not Acceptable");
        statusCodeMap.put(408, "Request Timeout");
        statusCodeMap.put(409, "Conflict");
        statusCodeMap.put(412, "Precondition Failed");
        statusCodeMap.put(413, "Payload Too Large");
        statusCodeMap.put(417, "Expectation Failed");
        statusCodeMap.put(421, "Misdirected Request");
        statusCodeMap.put(422, "Unprocessable Content");
        statusCodeMap.put(428, "Precondition Required");
        statusCodeMap.put(429, "Too Many Requests");
        statusCodeMap.put(431, "Request Header Fields Too Large");
        statusCodeMap.put(500, "Internal Server Error");
        statusCodeMap.put(502, "Bad Gateway");
        statusCodeMap.put(503, "Service Unavailable");
        statusCodeMap.put(504, "Gateway Timeout");
        statusCodeMap.put(505, "HTTP Version Not Supported");
        statusCodeMap.put(511, "Network Authentication Required");
        // Add more status codes as needed
    }
    public static String getStatusPhrase(int statusCode) {
        return statusCodeMap.getOrDefault(statusCode, "Unknown Status Code");
    }

    private static void printHelp() {
        System.out.println("Usage:");
        System.out.println("  java -jar api-tester.jar [URL] [arguments]");
        System.out.println("Required arguments:");
        System.out.println("  [URL]                   - Server URL.");
        System.out.println("Optional arguments:");
        System.out.println("  -totalCalls [value]     - Total number of calls across all threads. Default is 10000.");
        System.out.println("  -numThreads [value]     - Number of threads. Default is 12.");
        System.out.println("  -sleepTime [value]      - Sleep time in milliseconds between calls within a thread. Default is 0.");
        System.out.println("  -requestTimeOut [value] - HTTP request timeout in milliseconds. Default is 10000.");
        System.out.println("  -connectTimeOut [value] - HTTP request timeout in milliseconds. Default is 20000.");
        System.out.println("  -reuseConnects          - Attempts to reuse the connections if the server allows it.");
        System.out.println("Help:");
        System.out.println("  -? or --help - Display this help message.");
    }

    // Method to measure latency for a number of HTTP GET requests
    static class FetchData implements Runnable {
        private final HttpClient httpClient;
        private final List<Double> responseTimes;
        private final URL url;
        private final int sleepTime;
        private final int requestTimeOut;
        private final boolean reuseConnects;
        private final int threadID;
        private final int numCalls;

        public FetchData(HttpClient httpClient, List<Double> responseTimes, URL url, int sleepTime, int requestTimeOut,
                         boolean reuseConnects, int threadID, int numCalls) {

            this.httpClient = httpClient;
            this.responseTimes = responseTimes;
            this.url = url;
            this.sleepTime = sleepTime;
            this.requestTimeOut = requestTimeOut;
            this.reuseConnects = reuseConnects;
            this.threadID = threadID;
            this.numCalls = numCalls;
        }

        @Override
        public void run() {
            int httpStatusCode;
            HttpRequest request;
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();

            // Create the request builder with individual method calls to incorporate logic
            requestBuilder.timeout(Duration.ofMillis(requestTimeOut));
            requestBuilder.GET();
            try {requestBuilder.uri(url.toURI());} catch (URISyntaxException e) {
                System.err.printf("Error: Thread %2d - Incompatible URI '%s'\n", threadID, url);
                throw new RuntimeException(e);
            }
            if (reuseConnects)
                requestBuilder.header("Connection", "keep-alive");
            else
                requestBuilder.header("Connection", "close");
            request = requestBuilder.build();

            for (int i = 0; i < numCalls; i++) {
                Instant startTime;
                Instant endTime;
                try {
                    startTime = Instant.now();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    endTime = Instant.now();
                    httpStatusCode = response.statusCode();

                    double responseTime = (double) Duration.between(startTime, endTime).toNanos() / 1000000;
                    String status = httpStatusCode + " " + getStatusPhrase(httpStatusCode);
                    responseTimes.add(responseTime);

                    System.out.printf("Thread %2d.%-6d - Success: %s - Response time: %.2f ms\n", threadID, i, status, responseTime);

                    Thread.sleep(sleepTime);
                } catch (Exception e) {
                    System.err.printf("Thread %2d.%-6d - Request failed: %s\n", threadID, i, e.getMessage());
                }
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // URL to call
        URL url;
        // Total number of calls to make
        int  totalCalls = 10000;
        // Number of threads
        int  numThreads = 16;
        // Sleep time between calls in a thead (milliseconds)
        int  sleepTime = 0;
        // HTTP request timeout (milliseconds)
        int  requestTimeOut = 10000;
        // HTTP connection timeout (milliseconds)
        int  connectTimeOut = requestTimeOut * 3;
        // Reuse the HTTP connections
        boolean reuseConnects = false;
        // Java will not leave the connections open
        // boolean keepConnectsOpen = false;
        // HTTP-HTTPS client
        HttpClient httpClient;
        // SSL Context to allow all HTTPS certificates
        SSLContext sslContext;

        // Check if there are any arguments
        if (args.length < 1 ) {
            System.err.println("Error: Not enough arguments provided.");
            printHelp();
            return;
        }

        // Check for help flags
        if (args[0].equals("-?") || args[0].equals("--help")) {
            printHelp();
            return;
        }

        // Make sure that there is a URL
        if (args[0].startsWith("http")) {
            try {
                url = new URL(args[0]);
            } catch (MalformedURLException e) {
                System.err.printf("Error: \"%s\" is not a valid URL.\n", args[0]);
                printHelp();
                return;
            }
        } else {
            System.err.println("Error: [URL] must be the first parameter.");
            return;
        }

        // Parse command line arguments
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-totalCalls" -> {
                    try {
                        totalCalls = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.printf("Error: \"%s\" is not a valid integer.\n", args[i]);
                        printHelp();
                        return;
                    }
                }
                case "-numThreads" -> {
                    try {
                        numThreads = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.printf("Error: \"%s\" is not a valid integer.\n", args[i]);
                        printHelp();
                        return;
                    }
                }
                case "-sleepTime" -> {
                    try {
                        sleepTime = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.printf("Error: \"%s\" is not a valid integer.\n", args[i]);
                        printHelp();
                        return;
                    }
                }
                case "-requestTimeOut" -> {
                    try {
                        requestTimeOut = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.printf("Error: \"%s\" is not a valid integer.\n", args[i]);
                        printHelp();
                        return;
                    }
                }
                case "-connectTimeOut" -> {
                    try {
                        connectTimeOut = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.printf("Error: \"%s\" is not a valid integer.\n", args[i]);
                        printHelp();
                        return;
                    }
                }
                case "-reuseConnects" -> reuseConnects = true;
            }
        }

        // Force Java to allow the Connection header (Stupidly, Java will not let developers set some HTTP headers).
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "Connection");

        // For HTTPS, accept all certificates
        if (url.getProtocol().equalsIgnoreCase("https")) {
            try {
                // Create a trust manager that does not validate certificate chains
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() {
                                return null;
                            }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                };

                // Install the all-trusting trust manager
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                System.err.printf("Error: SSL context failed: %s\n", e.getMessage());
                return;
            }
            httpClient = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofMillis(connectTimeOut))
                    .build();
        } else {
            httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofMillis(connectTimeOut))
                    .build();
        }

        final int callsPerThread = totalCalls / numThreads;
        final int remainderCalls = totalCalls % numThreads;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Double> allResponseTimes = new ArrayList<>();

        Instant startTime = Instant.now();
        for (int i = 0; i <= numThreads; i++) {
            // Add one call to each thread number that is less than the mod of the total calls to compensate for the remainder
            final int numCalls = (i < remainderCalls) ? (callsPerThread + 1) : callsPerThread;
            executor.execute(new FetchData(httpClient,allResponseTimes, url, sleepTime, requestTimeOut, reuseConnects, i, numCalls));
        }

        // Shutdown the executor and wait for all tasks to finish
        executor.shutdown();
        boolean completed = executor.awaitTermination(1, TimeUnit.HOURS);
        Instant endTime = Instant.now();

        // Calculate the total time for the test in seconds
        double totalTime= (double) Duration.between(startTime, endTime).toNanos() / 1000000000;
        // Calculate the average requests per second
        double requestsPerSecond = (double) totalCalls / totalTime;

        // Calculate and print the average response time
        double totalResponseTime = 0;
        for (double time : allResponseTimes) {
            totalResponseTime += time;
        }
        double averageResponseTime = totalResponseTime / allResponseTimes.size();

        System.out.printf("Total thread count: %d\n", numThreads);
        System.out.printf("Total test time: %.2f s\n", totalTime);
        System.out.printf("Average response time: %.2f ms\n", averageResponseTime);
        System.out.printf("Average requests per second: %.2f\n", requestsPerSecond);

        if (completed)
            System.out.println("All threads have finished.");
        else
            System.out.println("Some threads terminated prematurely.");
    }
}
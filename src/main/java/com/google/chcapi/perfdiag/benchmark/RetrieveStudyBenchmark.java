// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.chcapi.perfdiag.benchmark;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import java.io.PrintStream;

import picocli.CommandLine.Mixin;
import picocli.CommandLine.Command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;

import com.google.chcapi.perfdiag.model.Instance;
import com.google.chcapi.perfdiag.benchmark.config.DicomStudyConfig;
import com.google.chcapi.perfdiag.profiler.HttpRequestProfiler;
import com.google.chcapi.perfdiag.profiler.HttpRequestProfilerFactory;
import com.google.chcapi.perfdiag.profiler.HttpRequestStatistics;

/**
 * This benchmark shows how fast it can be to retrieve a whole study with Google Cloud Healthcare
 * Imaging API. It involves sending request to get instance information (QIDO) and sending
 * paralleled GET requests to retrieve each instance (WADO).
 * 
 * @author Mikhail Ukhlin
 */
@Command
public class RetrieveStudyBenchmark extends Benchmark {
  
  /**
   * DICOM study configuration from command line.
   */
  @Mixin
  protected DicomStudyConfig dicomStudyConfig;
  
  /**
   * Aggregated statistics for retrieve study instance requests.
   */
  private final HttpRequestStatistics retrieveInstanceStats = new HttpRequestStatistics();
  
  /**
   * Thread pool.
   */
  private final ExecutorService pool = Executors.newFixedThreadPool(20);
  
  @Override
  protected void runIteration(int iteration, PrintStream output) throws Exception {
    // Fetch list of available study instances
    final List<Instance> instances = fetchStudyInstances();
    
    // Create separate task for each study instance
    final List<Callable<HttpRequestProfiler>> tasks = new ArrayList<>();
    for (Instance instance : instances) {
      final String seriesId = instance.getSeriesUID();
      final String instanceId = instance.getInstanceUID();
      if (!(seriesId == null || instanceId == null)) {
        tasks.add(new Callable<HttpRequestProfiler>() {
          @Override public HttpRequestProfiler call() throws Exception {
            final HttpRequestProfiler request =
                HttpRequestProfilerFactory.createRetrieveDicomStudyInstanceRequest(dicomStudyConfig,
                    seriesId, instanceId);
            final int length = request.execute().length;
            printRequestExecuted(request, length);
            return request;
          }
        });
      }
    }
    
    // Wait for completion and update statistics
    for (Future<HttpRequestProfiler> future : pool.invokeAll(tasks)) {
      try {
        final HttpRequestProfiler request = future.get();
        output.println(request.toCSVString(iteration));
        retrieveInstanceStats.addProfile(request);
      } catch (Exception e) {
        printRequestFailed(e);
      }
    }
  }
  
  @Override
  protected void printMetrics() {
    printStatistics(retrieveInstanceStats);
  }
  
  private List<Instance> fetchStudyInstances() throws Exception {
    final HttpRequestProfiler request =
        HttpRequestProfilerFactory.createListDicomStudyInstancesRequest(dicomStudyConfig);
    final List<Instance> instances = MAPPER.readValue(request.execute(),
        new TypeReference<List<Instance>>() {});
    printInstancesFound(instances.size());
    printStatistics(request);
    return instances;
  }
  
  /* Object mapper to convert JSON response */
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  
}

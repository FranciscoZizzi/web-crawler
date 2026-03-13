# Professional Web Crawler System Design

This document outlines the architectural choices, trade-offs, and implementation details of the `crawler-core` module. Designed as part of a technical challenge, the system prioritizes high concurrency, extensible content processing, and a clear path from local prototyping to distributed multi-node production.

## 1. Architectural Overview

The system follows an **Interface-First Design** pattern. The core logic is decoupled from side-effect-heavy operations (I/O, Storage, Logging), allowing for easy unit testing and future-proofing.

### Core Components
- **`CrawlerOrchestrator`**: The central controller. It manages the crawl lifecycle, coordinating between the Frontier, Downloader, and Content Handlers.
- **`URLFrontier`**: A sophisticated Mercator-style implementation that handles URL prioritization (Front Queues) and host-bucketed politeness (Back Queues).
- **`HTMLDownloader`**: An asynchronous engine with a dispatcher-worker pattern to manage concurrent HTTP requests.
- **`ContentHandler` Pipeline**: An extensible plugin system. The orchestrator passes raw content through a chain of handlers (e.g., `HtmlHandler` for links, `ImageDownloader` for media).
- **`URLStorage` & `ContentStorage`**: Abstracted deduplication layers.

## 2. Key Design Patterns

### Mercator-Style Frontier
The choice of a Mercator-inspired frontier is the system's "politeness engine."
- **Front Queues**: URLs are assigned a priority (e.g., `.edu` higher than `.com`) and drained into back queues in batches.
- **Back Queues (Bucket Locking)**: URLs are bucketed by host. A per-bucket lock (local or distributed) ensures the crawler never hits the same server with parallel requests, fundamentally protecting target server stability.

### Concurrency via Coroutines
Instead of heavy OS threads, the system utilizes **Kotlin Coroutines**.
- **Efficiency**: Allows launching thousands of concurrent "virtual threads" for waiting on I/O.
- **Structured Concurrency**: Using `coroutineScope` ensure that all child tasks (crawling, logging, sink processing) are properly cancelled or completed before the orchestrator finishes.

## 3. Scaling & Distributed Operations

A priority of this design was ensuring the transition from local memory to a distributed cluster requires **zero code changes** in the core orchestrator.

### Pluggable Primitives
We introduced two key abstractions:
- **`WorkQueueStorage`**: Can be swapped from `Memory` to `Redis` (Lists) or `RabbitMQ`.
- **`QueueLockManager`**: Can be swapped from atomic local maps to `Redis` (using `SET NX EX`) for global politeness locking across a cluster.

## 4. Technical Trade-offs

| Choice | Trade-off | Rationale |
| :--- | :--- | :--- |
| **Strict Politeness** | Throughput | We prioritize being a "good citizen" over raw speed. Serializing requests to single domains prevents IP blacklisting and server crashes. |
| **Bloom Filters vs. Sets** | Precision | In production, `URLStorage` should use Bloom Filters (approximate) to save 90% memory on billions of URLs. The current local `Set` provides 100% precision for prototyping. |
| **DNS Caching** | Stale IPs | We cache DNS results locally to avoid blocking `getByName` calls on every link. We accept minor staleness in exchange for significantly lower latency. |
Lack of consistent hashing

## 5. AI Integration in Development

AI was utilized during the development lifecycle to accelerate the development process.
### First Stages
Through a conversation with AI, I was able to reach a better understanding of how a web-crawler works and the common pitfalls and challenges associated with them. 
Using the compiled information I defined: 
- The scope of the project
- A basic initial structure to start the development
- A series of steps to follow to implement the web crawler in an incremental way, slowly adding complexity
- Initial prompts for each step to feed into an AI agent
UML diagrams were generated to better understand the target architecture
### Implementation
I decided to try out Antigravity, an IDE with AI capabilities, to help me with the implementation.
Using the previously mentioned steps, I prompted the agent to generate the code for each step. After each prompt I carefully reviewed the code and architecture, interveining when necessary to correct errors or improve the design and making sure to facilitate the implementation of the following steps.
After a couple steps, a basic functioning crawler was implemented. Unit tests were implemented for each component as well as an integration test for the CrawlerOrchestrator.
Making an integration test was crucial to understand the interactions between components and see what was missing and where the design could be improved.
After all tests were passing, I started iterating upon the design to cover common issues or add optimizations discussed in the first steps.
The tests were crucial to detect bugs while iterating on the design.
### Documentation
I used AI to help me generate the documentation for the project. Throughout the process I asked the AI to log the design decisions and trade-offs made during the development process to then compile into a concise document.

## 6. Future Enhancements
- **Dynamic Priorities**: Adjust URL priority based on PageRank or update frequency discovery.
- **Headless Browser Support**: Integrate a handler that utilizes Playwright or Selenium for Javascript-heavy sites.
- **Consistent Hashing**: Allow multiple nodes to work in parallel while mantaining politeness.
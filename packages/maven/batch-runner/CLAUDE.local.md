# Maven Batch Runner - Phase 2 & 3 Optimization Strategy

## Current Performance Baseline (Phase 1)

```
Container initialization (one-time):  34ms
Per-execution overhead (cached):      1.3ms average
  - Maven project scanning:           ~0.7-0.8ms (BOTTLENECK)
  - POM parsing & model building:     ~0.3-0.4ms
  - Execution context setup:          ~0.1-0.2ms
```

**Current throughput**: ~190 executions/second (estimated)

## Identified Bottlenecks

### 🔴 Primary Bottleneck: Project Scanning (0.7-0.8ms per execution)
Each Maven execution scans for projects and parses POMs:
- Filesystem traversal looking for pom.xml files
- POM model building (interpolation, inheritance, etc)
- Dependency resolution initialization

**Impact**: This happens even for simple tasks like `--version`

### 🟡 Secondary: Execution Context Setup (0.1-0.2ms per execution)
- Session/request initialization
- Plugin manager setup
- Lifecycle participant creation

## Phase 2: MavenProject Caching

### Objective
Cache parsed MavenProject objects keyed by POM path + last-modified timestamp.
**Expected gain**: 0.3-0.5ms per execution
**Target**: 0.8-1.0ms per execution

### Rules for Implementation

1. **Cache Key Design**
   - Key: `(pomPath, lastModifiedTime, activeProfiles)`
   - Only cache projects that haven't changed
   - Invalidate on POM modification
   - Handle profile changes correctly

2. **Lifecycle Binding**
   - Cache at: After `ProjectBuilder.build()` returns MavenProject
   - Before: Any goal execution uses the cached project
   - Invalidation: When POM timestamp changes or profiles differ

3. **Thread Safety**
   - Use `ConcurrentHashMap<ProjectCacheKey, MavenProject>`
   - Ensure cached objects are immutable or copied
   - Test with parallel task execution

4. **Measurement**
   - Add `getCacheStats()` method to track hit/miss rates
   - Log "Cache hit" or "Cache miss" at DEBUG level
   - Benchmark improvement with unit/integration tests

## Phase 3: Execution Plan Caching

### Objective
Cache execution plans (the sequence of mojo executions) per goal set.
**Expected gain**: 0.2-0.3ms per execution
**Target**: <0.5ms per execution

### Rules for Implementation

1. **Cache Key Design**
   - Key: `(projectPath, goals, profiles, properties)`
   - Different goals = different plans
   - Plans include plugin configuration + lifecycle binding

2. **Cache Scope**
   - Cache at: After lifecycle plan is calculated
   - Reuse: When same goals + project + profiles executed again
   - Clear: When plugin dependencies or versions change

3. **Integration Points**
   - Hook into Maven's lifecycle processor
   - Intercept at: `MavenExecutionPlan` construction
   - Return cached plan if available

4. **Validation**
   - Plans must include checksums of plugin versions
   - Invalidate if plugins change
   - Log plan cache usage at DEBUG level

## Implementation Approach

### Order of Implementation
1. **First**: Implement Phase 2 (MavenProject caching)
   - Lower risk, simpler API
   - 0.3-0.5ms gain
   - Validates caching approach

2. **Then**: Implement Phase 3 (Execution plan caching)
   - More complex, touches lifecycle engine
   - 0.2-0.3ms gain
   - Combined should reach <0.5ms per execution

### Testing Strategy

1. **Unit Tests**
   - Cache hit/miss correctness
   - Cache invalidation on POM changes
   - Profile handling edge cases

2. **Performance Tests**
   - Add benchmarks for Phase 2 & 3
   - Compare cache hit vs miss performance
   - Measure cumulative improvement

3. **Integration Tests**
   - Test with real Maven projects
   - Verify builds produce identical results (cached vs non-cached)
   - Test cache invalidation scenarios

## Key Principles

1. **Correctness First**
   - Caching must be transparent to build results
   - Cached execution must produce identical output
   - No silent failures due to stale cache

2. **Observe Behavior**
   - Log cache operations at DEBUG level
   - Track hit rates and cache sizes
   - Measure actual performance gains with benchmarks

3. **Incremental Approach**
   - Complete Phase 2 fully before starting Phase 3
   - Run benchmarks after each phase
   - Only proceed if performance improves

4. **Avoid Over-Engineering**
   - Start with simple caching strategies
   - Don't pre-optimize
   - Let benchmarks guide optimization

## Success Criteria

### Phase 2 Success
- ✅ Per-execution overhead: 0.8-1.0ms (down from 1.3ms)
- ✅ Cache hit rate: >90% for repeated tasks
- ✅ All tests pass
- ✅ Zero correctness regressions

### Phase 3 Success
- ✅ Per-execution overhead: <0.5ms
- ✅ Combined improvement: >50% over Phase 1 baseline
- ✅ All tests pass
- ✅ Production-ready caching

### Overall Goal
```
Before (Maven Invoker):     100-500ms per task
Phase 1 (Container reuse):  1.3ms per task (75-385x improvement)
Phase 2 (Project caching):  0.8-1.0ms per task (100-625x improvement)
Phase 3 (Plan caching):     <0.5ms per task (200-1000x improvement)
```

## Code Organization

### New Classes to Create
- `MavenProjectCache` - Manages MavenProject caching
- `ExecutionPlanCache` - Manages execution plan caching
- `CacheKey` interfaces - Define cache key generation

### Existing Classes to Modify
- `CachedMavenExecutor` - Integrate caching layers

### Avoid Modifying
- EmbeddedMavenExecutor - It's external, don't fork it
- Maven core classes - Work with public APIs only

## Debugging Tips

1. **To see cache operations**:
   - Enable DEBUG logging: `mvn -X ...`
   - Look for "Cache hit" and "Cache miss" log messages

2. **To measure cache effectiveness**:
   - Check `getCacheStats()` after batch execution
   - Compare execution times with/without cache

3. **To diagnose cache misses**:
   - Log cache key calculations
   - Verify POM timestamps are updated correctly
   - Check profile handling

## Related Files

- `CachedMavenExecutor.kt` - Integration point for caching
- `CachedMavenExecutorTest.kt` - Unit tests for executor

---

**Strategy Document**: 2025-11-03
**Phase 1 Status**: Complete ✅
**Phase 2 Status**: Ready to implement
**Phase 3 Status**: Ready to implement (after Phase 2)

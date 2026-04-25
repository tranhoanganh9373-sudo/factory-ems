package com.ems.timeseries.rollup;

import java.util.List;
import java.util.Optional;

/**
 * Rollup 流水线对"测点目录"的抽象。Plan 1.2 由 ems-app 提供 MeterRepository 适配器实现。
 * 保持 ems-timeseries 不直接依赖 ems-meter（避免反向耦合）。
 */
public interface MeterCatalogPort {

    /** 所有 enabled = true 的测点。 */
    List<RollupComputeService.MeterCtx> findAllEnabled();

    /** 按 id 查测点；retry / 补跑 API 使用。 */
    Optional<RollupComputeService.MeterCtx> findById(Long meterId);
}

package com.ems.billing.service.impl;

import com.ems.billing.entity.BillPeriod;
import com.ems.billing.repository.BillPeriodRepository;
import com.ems.cost.service.BillPeriodLookupPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BillPeriodLookupPort 实现：从 bill_period 表读边界。
 *
 * 放在 ems-billing 而不是 ems-cost，因为 BillPeriodRepository 属于 ems-billing；
 * ems-cost 不能反向依赖 ems-billing（会形成循环依赖）。Port 接口本身在 ems-cost 里声明。
 */
@Service
public class BillPeriodLookupAdapter implements BillPeriodLookupPort {

    private final BillPeriodRepository billPeriodRepository;

    public BillPeriodLookupAdapter(BillPeriodRepository billPeriodRepository) {
        this.billPeriodRepository = billPeriodRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public BillPeriodBoundaries findBoundariesById(Long billPeriodId) {
        BillPeriod p = billPeriodRepository.findById(billPeriodId)
                .orElseThrow(() -> new BillPeriodNotFoundException(billPeriodId));
        return new BillPeriodBoundaries(p.getPeriodStart(), p.getPeriodEnd());
    }
}

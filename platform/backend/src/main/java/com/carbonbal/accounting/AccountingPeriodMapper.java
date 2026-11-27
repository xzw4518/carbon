package com.carbonbal.accounting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountingPeriodMapper extends BaseMapper<AccountingPeriod> {

    /** 期间行锁：同一租户同一期间的并发重算串行化，避免死锁与重复建期。 */
    @Select("select * from accounting_period where org_id = #{orgId} and period = #{period} for update")
    AccountingPeriod lockForUpdate(@Param("orgId") Long orgId, @Param("period") String period);

    /** 幂等建期：并发首次计算时用唯一键冲突直接拿排他锁，避免 S→X 锁升级死锁。 */
    @Insert("insert into accounting_period(org_id, period, status, calc_version, version, created_at, updated_at) "
            + "values(#{orgId}, #{period}, 'draft', 0, 0, now(), now()) "
            + "on duplicate key update period = period")
    int ensureExists(@Param("orgId") Long orgId, @Param("period") String period);
}

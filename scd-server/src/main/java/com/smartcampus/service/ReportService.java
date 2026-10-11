package com.smartcampus.service;

import com.smartcampus.vo.BusinessDataVO;
import com.smartcampus.vo.OrderReportVO;
import com.smartcampus.vo.SalesTop10ReportVO;
import com.smartcampus.vo.TurnoverReportVO;
import com.smartcampus.vo.UserReportVO;

import javax.servlet.http.HttpServletResponse;
import java.time.LocalDate;

public interface ReportService {

    /**
     * 统计指定时间区间内的营业额数据
     *
     * @param begin
     * @param end
     * @return
     */
    TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end);

    /**
     * 统计指定时间区间内的用户数据
     *
     * @param begin
     * @param end
     * @return
     */
    UserReportVO getUserStatistics(LocalDate begin, LocalDate end);

    /**
     * 统计指定时间区间内的订单数据
     *
     * @param begin
     * @param end
     * @return
     */
    OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end);

    /**
     * 统计指定时间区间内的销量排名 Top10
     *
     * @param begin
     * @param end
     * @return
     */
    SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end);

    /**
     * 统计指定时间区间内的运营数据（营业额、有效订单、完成率、客单价、新增用户）
     *
     * @param begin
     * @param end
     * @return
     */
    BusinessDataVO getBusinessData(java.time.LocalDateTime begin, java.time.LocalDateTime end);

    /**
     * 导出运营数据报表（Excel，基于 Apache POI）
     *
     * @param response
     */
    void exportBusinessData(HttpServletResponse response);
}

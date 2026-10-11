package com.smartcampus.service.impl;

import com.smartcampus.dto.GoodsSalesDTO;
import com.smartcampus.entity.Orders;
import com.smartcampus.mapper.OrderMapper;
import com.smartcampus.mapper.UserMapper;
import com.smartcampus.service.ReportService;
import com.smartcampus.vo.BusinessDataVO;
import com.smartcampus.vo.OrderReportVO;
import com.smartcampus.vo.SalesTop10ReportVO;
import com.smartcampus.vo.TurnoverReportVO;
import com.smartcampus.vo.UserReportVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据统计业务实现
 * <p>
 * 报表导出使用 Apache POI 动态生成 xlsx（不依赖外部模板文件，开箱即用）。
 */
@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    /**
     * SXSSF 滑动窗口大小：内存中保留的行数，超出部分刷写到临时文件
     */
    private static final int EXCEL_WINDOW_SIZE = 100;

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;

    /**
     * 统计指定时间区间内的营业额数据
     *
     * @param begin
     * @param end
     * @return
     */
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = getDateList(begin, end);

        List<Double> turnoverList = new ArrayList<>();
        for (LocalDate date : dateList) {
            Map<String, Object> map = new HashMap<>();
            map.put("begin", LocalDateTime.of(date, LocalTime.MIN));
            map.put("end", LocalDateTime.of(date, LocalTime.MAX));
            // 营业额只统计已完成的订单
            map.put("status", Orders.COMPLETED);
            Double turnover = orderMapper.sumByMap(map);
            turnover = turnover == null ? 0.0 : turnover;
            turnoverList.add(turnover);
        }

        return TurnoverReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .turnoverList(StringUtils.join(turnoverList, ","))
                .build();
    }

    /**
     * 统计指定时间区间内的用户数据
     *
     * @param begin
     * @param end
     * @return
     */
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = getDateList(begin, end);

        List<Integer> newUserList = new ArrayList<>();
        List<Integer> totalUserList = new ArrayList<>();

        for (LocalDate date : dateList) {
            // 截至当天的用户总量
            Map<String, Object> map = new HashMap<>();
            map.put("end", LocalDateTime.of(date, LocalTime.MAX));
            Integer totalUser = userMapper.countByMap(map);
            totalUserList.add(totalUser == null ? 0 : totalUser);

            // 当天新增用户
            map.put("begin", LocalDateTime.of(date, LocalTime.MIN));
            Integer newUser = userMapper.countByMap(map);
            newUserList.add(newUser == null ? 0 : newUser);
        }

        return UserReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .totalUserList(StringUtils.join(totalUserList, ","))
                .newUserList(StringUtils.join(newUserList, ","))
                .build();
    }

    /**
     * 统计指定时间区间内的订单数据
     *
     * @param begin
     * @param end
     * @return
     */
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = getDateList(begin, end);

        List<Integer> orderCountList = new ArrayList<>();
        List<Integer> validOrderCountList = new ArrayList<>();

        for (LocalDate date : dateList) {
            Map<String, Object> map = new HashMap<>();
            map.put("begin", LocalDateTime.of(date, LocalTime.MIN));
            map.put("end", LocalDateTime.of(date, LocalTime.MAX));

            // 每日订单总数
            Integer orderCount = orderMapper.countByMap(map);
            orderCountList.add(orderCount == null ? 0 : orderCount);

            // 每日有效订单数（已完成）
            map.put("status", Orders.COMPLETED);
            Integer validOrderCount = orderMapper.countByMap(map);
            validOrderCountList.add(validOrderCount == null ? 0 : validOrderCount);
        }

        // 时间区间内的订单总数
        Integer totalOrderCount = orderCountList.stream().reduce(Integer::sum).orElse(0);
        // 时间区间内的有效订单数
        Integer validOrderCount = validOrderCountList.stream().reduce(Integer::sum).orElse(0);

        Double orderCompletionRate = 0.0;
        if (totalOrderCount != 0) {
            orderCompletionRate = validOrderCount.doubleValue() / totalOrderCount;
        }

        return OrderReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .orderCountList(StringUtils.join(orderCountList, ","))
                .validOrderCountList(StringUtils.join(validOrderCountList, ","))
                .totalOrderCount(totalOrderCount)
                .validOrderCount(validOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .build();
    }

    /**
     * 统计指定时间区间内的销量排名 Top10
     *
     * @param begin
     * @param end
     * @return
     */
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {
        Map<String, Object> map = new HashMap<>();
        map.put("begin", LocalDateTime.of(begin, LocalTime.MIN));
        map.put("end", LocalDateTime.of(end, LocalTime.MAX));

        List<GoodsSalesDTO> salesTop10 = orderMapper.getSalesTop10(map);

        List<String> names = salesTop10.stream().map(GoodsSalesDTO::getName).collect(Collectors.toList());
        List<Integer> numbers = salesTop10.stream().map(GoodsSalesDTO::getNumber).collect(Collectors.toList());

        return SalesTop10ReportVO.builder()
                .nameList(StringUtils.join(names, ","))
                .numberList(StringUtils.join(numbers, ","))
                .build();
    }

    /**
     * 统计指定时间区间内的运营数据
     *
     * @param begin
     * @param end
     * @return
     */
    public BusinessDataVO getBusinessData(LocalDateTime begin, LocalDateTime end) {
        Map<String, Object> map = new HashMap<>();
        map.put("begin", begin);
        map.put("end", end);

        // 查询总订单数
        Integer totalOrderCount = orderMapper.countByMap(map);

        map.put("status", Orders.COMPLETED);
        // 营业额
        Double turnover = orderMapper.sumByMap(map);
        turnover = turnover == null ? 0.0 : turnover;

        // 有效订单数
        Integer validOrderCount = orderMapper.countByMap(map);

        Double orderCompletionRate = 0.0;
        Double unitPrice = 0.0;
        if (totalOrderCount != null && totalOrderCount != 0) {
            orderCompletionRate = validOrderCount.doubleValue() / totalOrderCount;
        }
        if (validOrderCount != null && validOrderCount != 0) {
            // 平均客单价 = 营业额 / 有效订单数
            unitPrice = turnover / validOrderCount;
        }

        // 新增用户数
        Map<String, Object> map2 = new HashMap<>();
        map2.put("begin", begin);
        map2.put("end", end);
        Integer newUsers = userMapper.countByMap(map2);

        return BusinessDataVO.builder()
                .turnover(turnover)
                .validOrderCount(validOrderCount == null ? 0 : validOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .unitPrice(unitPrice)
                .newUsers(newUsers == null ? 0 : newUsers)
                .build();
    }

    /**
     * 导出运营数据报表（Excel）
     *
     * @param response
     */
    public void exportBusinessData(HttpServletResponse response) {
        // 1、查询最近 30 天的运营数据（不含当天）
        LocalDate dateBegin = LocalDate.now().minusDays(30);
        LocalDate dateEnd = LocalDate.now().minusDays(1);
        LocalDateTime beginTime = LocalDateTime.of(dateBegin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(dateEnd, LocalTime.MAX);

        BusinessDataVO businessDataVO = getBusinessData(beginTime, endTime);

        // 2、通过 POI 动态生成 Excel
        //    使用 SXSSFWorkbook（滑动窗口）：内存中仅保留最近 EXCEL_WINDOW_SIZE 行，
        //    其余行即时刷写到临时文件，避免报表数据量增长时一次性占用大量堆内存
        SXSSFWorkbook excel = new SXSSFWorkbook(EXCEL_WINDOW_SIZE);
        try {
            // ===== Sheet1：概览数据 =====
            Sheet sheet = excel.createSheet("概览数据");
            sheet.setColumnWidth(0, 20 * 256);
            sheet.setColumnWidth(1, 18 * 256);
            sheet.setColumnWidth(2, 18 * 256);
            sheet.setColumnWidth(3, 18 * 256);
            sheet.setColumnWidth(4, 18 * 256);
            sheet.setColumnWidth(5, 18 * 256);

            CellStyle titleStyle = createTitleStyle(excel);
            CellStyle headerStyle = createHeaderStyle(excel);

            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("智慧园区即时零售运营数据报表");
            titleCell.setCellStyle(titleStyle);

            Row rangeRow = sheet.createRow(1);
            rangeRow.createCell(0).setCellValue("时间：" + dateBegin + " 至 " + dateEnd);

            String[] headers = {"日期", "营业额", "有效订单数", "订单完成率", "平均客单价", "新增用户数"};
            Row headerRow = sheet.createRow(2);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            Row dataRow = sheet.createRow(3);
            dataRow.createCell(0).setCellValue(dateBegin + " 至 " + dateEnd);
            dataRow.createCell(1).setCellValue(businessDataVO.getTurnover());
            dataRow.createCell(2).setCellValue(businessDataVO.getValidOrderCount());
            dataRow.createCell(3).setCellValue(formatRate(businessDataVO.getOrderCompletionRate()));
            dataRow.createCell(4).setCellValue(businessDataVO.getUnitPrice());
            dataRow.createCell(5).setCellValue(businessDataVO.getNewUsers());

            // ===== Sheet2：明细数据 =====
            Sheet detailSheet = excel.createSheet("明细数据");
            for (int i = 0; i < headers.length; i++) {
                detailSheet.setColumnWidth(i, 20 * 256);
            }

            Row detailHeader = detailSheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = detailHeader.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (LocalDate date : getDateList(dateBegin, dateEnd)) {
                BusinessDataVO vo = getBusinessData(
                        LocalDateTime.of(date, LocalTime.MIN),
                        LocalDateTime.of(date, LocalTime.MAX));

                Row row = detailSheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(date.toString());
                row.createCell(1).setCellValue(vo.getTurnover());
                row.createCell(2).setCellValue(vo.getValidOrderCount());
                row.createCell(3).setCellValue(formatRate(vo.getOrderCompletionRate()));
                row.createCell(4).setCellValue(vo.getUnitPrice());
                row.createCell(5).setCellValue(vo.getNewUsers());
            }

            // 3、输出到响应流
            ServletOutputStream out = response.getOutputStream();
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment;filename=businessData.xlsx");
            excel.write(out);
            out.flush();
            out.close();
        } catch (Exception e) {
            log.error("导出运营数据报表失败", e);
        } finally {
            try {
                // 删除 SXSSF 写入的临时文件并释放工作簿资源
                excel.dispose();
                excel.close();
            } catch (Exception e) {
                log.warn("释放 Excel 工作簿资源失败", e);
            }
        }
    }

    private String formatRate(Double rate) {
        if (rate == null) {
            return "0.00%";
        }
        return String.format("%.2f%%", rate * 100);
    }

    private CellStyle createTitleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    /**
     * 获取从 begin 到 end（含首尾）的日期列表
     */
    private List<LocalDate> getDateList(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = new ArrayList<>();
        dateList.add(begin);
        while (!begin.equals(end)) {
            begin = begin.plusDays(1);
            dateList.add(begin);
        }
        return dateList;
    }
}

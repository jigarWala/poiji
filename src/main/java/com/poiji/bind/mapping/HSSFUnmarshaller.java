package com.poiji.bind.mapping;

import com.poiji.annotation.ExcelCell;
import com.poiji.annotation.ExcelCellName;
import com.poiji.annotation.ExcelCellRange;
import com.poiji.annotation.ExcelRow;
import com.poiji.bind.Unmarshaller;
import com.poiji.config.Casting;
import com.poiji.exception.IllegalCastException;
import com.poiji.option.PoijiOptions;
import com.poiji.util.ReflectUtil;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static java.lang.String.valueOf;

/**
 * This is the main class that converts the excel sheet fromExcel Java object
 * Created by hakan on 16/01/2017.
 */
abstract class HSSFUnmarshaller implements Unmarshaller {

    private final DataFormatter dataFormatter;
    protected final PoijiOptions options;
    private final Casting casting;
    private Map<String, Integer> titles;
    private int limit;
    private int readEvery;
    private int skipEvery;

    HSSFUnmarshaller(PoijiOptions options) {
        this.options = options;
        this.limit = options.getLimit();
        this.readEvery = options.getReadEvery();
        dataFormatter = new DataFormatter();
        titles = new HashMap<>();
        casting = options.getCasting();
    }

    @Override
    public <T> void unmarshal(Class<T> type, Consumer<? super T> consumer) {
        Workbook workbook = workbook();
        Optional<String> maybeSheetName = SheetNameExtractor.getSheetName(type, options);

        Sheet sheet = this.getSheetToProcess(workbook, options, maybeSheetName.orElse(null));

        int skip = options.skip();
        int maxPhysicalNumberOfRows = sheet.getPhysicalNumberOfRows() + 1 - skip;

        loadColumnTitles(sheet, maxPhysicalNumberOfRows);

        for (Row currentRow : sheet) {
            if (!skip(currentRow, skip) && !isRowEmpty(currentRow)) {

                if(currentRow.getRowNum() > limit)
                    return;

                if(readEvery>0) {
                    T t = deserialize0(currentRow, type);
                    consumer.accept(t);
                    readEvery--;
                    if(readEvery==0) {
                        if(options.getSkipEvery() == 0)
                            readEvery = options.getReadEvery();
                        else
                            skipEvery = options.getSkipEvery();
                    }
                }
                else {
                    skipEvery--;
                    if(skipEvery==0)
                        readEvery = options.getReadEvery();
                }
            }
        }
    }

    private Sheet getSheetToProcess(Workbook workbook, PoijiOptions options, String sheetName) {
        int nonHiddenSheetIndex = 0;
        int requestedIndex = options.sheetIndex();
        Sheet sheet = null;
        if (options.ignoreHiddenSheets()) {
          for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (!workbook.isSheetHidden(i) && !workbook.isSheetVeryHidden(i)) {
              if (sheetName == null) {
                if (nonHiddenSheetIndex == requestedIndex) {
                  return workbook.getSheetAt(i);
                }
              } else {
                if (workbook.getSheetName(i).equalsIgnoreCase(sheetName)) {
                  return workbook.getSheetAt(i);
                }
              }
              nonHiddenSheetIndex++;
            }
          }
        } else {
          if (sheetName == null) {
            sheet = workbook.getSheetAt(requestedIndex);
          } else {
            sheet = workbook.getSheet(sheetName);
          }
        }
        return sheet;
      }

    private void loadColumnTitles(Sheet sheet, int maxPhysicalNumberOfRows) {
        if (maxPhysicalNumberOfRows > 0) {
            int row = options.getHeaderStart();
            Row firstRow = sheet.getRow(row);
            for (Cell cell : firstRow) {
                titles.put(cell.getStringCellValue(), cell.getColumnIndex());
            }
        }
    }

    private <T> T deserialize0(Row currentRow, Class<T> type) {
        T instance = ReflectUtil.newInstanceOf(type);
        return setFieldValue(currentRow, type, instance);
    }

    private <T> T tailSetFieldValue(Row currentRow, Class<? super T> type, T instance) {
        for (Field field : type.getDeclaredFields()) {
            ExcelRow excelRow = field.getAnnotation(ExcelRow.class);
            if (excelRow != null) {
                Object o;
                o = casting.castValue(field.getType(), valueOf(currentRow.getRowNum()), currentRow.getRowNum(), -1, options);
                setFieldData(instance, field, o);
            }
            ExcelCellRange excelCellRange = field.getAnnotation(ExcelCellRange.class);
            if (excelCellRange != null) {
                Class<?> o = field.getType();
                Object ins = ReflectUtil.newInstanceOf(o);
                for (Field f : o.getDeclaredFields()) {
                    tailSetFieldValue(currentRow, ins, f);
                }
                setFieldData(instance, field, ins);
            } else {
                tailSetFieldValue(currentRow, instance, field);
            }
        }
        return instance;
    }

    private <T> void tailSetFieldValue(Row currentRow, T instance, Field field) {
        ExcelCell index = field.getAnnotation(ExcelCell.class);
        if (index != null) {
            constructTypeValue(currentRow, instance, field, index.value());
        } else {
            ExcelCellName excelCellName = field.getAnnotation(ExcelCellName.class);
            if (excelCellName != null) {
                Integer titleColumn = titles.get(excelCellName.value());
                if (titleColumn != null) {
                    constructTypeValue(currentRow, instance, field, titleColumn);
                }
            }
        }
    }

    private <T> void constructTypeValue(Row currentRow, T instance, Field field, int column) {
        Class<?> fieldType = field.getType();
        Cell cell = currentRow.getCell(column);

        if (cell != null) {
            String value = dataFormatter.formatCellValue(cell);
            Object o = casting.castValue(fieldType, value, currentRow.getRowNum(), column, options);
            setFieldData(instance, field, o);
        }
    }

    private <T> void setFieldData(T instance, Field field, Object o) {
        try {
            field.setAccessible(true);
            field.set(instance, o);
        } catch (IllegalAccessException e) {
            throw new IllegalCastException("Unexpected cast type {" + o + "} of field" + field.getName());
        }
    }

    private <T> T setFieldValue(Row currentRow, Class<? super T> subclass, T instance) {
        return subclass == null
                ? instance
                : tailSetFieldValue(currentRow, subclass, setFieldValue(currentRow, subclass.getSuperclass(), instance));
    }

    private boolean skip(final Row currentRow, int skip) {
        return currentRow.getRowNum() + 1 <= skip;
    }

    private boolean isRowEmpty(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    protected abstract Workbook workbook();
}

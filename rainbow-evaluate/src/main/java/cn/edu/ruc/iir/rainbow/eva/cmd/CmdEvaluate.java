package cn.edu.ruc.iir.rainbow.eva.cmd;

import cn.edu.ruc.iir.rainbow.common.cmd.Command;
import cn.edu.ruc.iir.rainbow.common.cmd.Receiver;
import cn.edu.ruc.iir.rainbow.common.exception.ExceptionHandler;
import cn.edu.ruc.iir.rainbow.common.exception.ExceptionType;
import cn.edu.ruc.iir.rainbow.common.exception.MetaDataException;
import cn.edu.ruc.iir.rainbow.common.metadata.MetaDataStat;
import cn.edu.ruc.iir.rainbow.common.util.LogFactory;
import cn.edu.ruc.iir.rainbow.eva.LocalEvaluator;
import cn.edu.ruc.iir.rainbow.eva.SparkEvaluator;
import cn.edu.ruc.iir.rainbow.eva.domain.Column;
import cn.edu.ruc.iir.rainbow.eva.metrics.LocalMetrics;
import cn.edu.ruc.iir.rainbow.eva.metrics.StageMetrics;
import org.apache.commons.logging.Log;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import parquet.hadoop.metadata.ParquetMetadata;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by hank on 17-5-4.
 */
public class CmdEvaluate implements Command
{
    private Log log = LogFactory.Instance().getLog();

    private Receiver receiver = null;

    @Override
    public void setReceiver(Receiver receiver)
    {
        this.receiver = receiver;
    }

    /**
     *
     * @param params local ordered_hdfs_path unordered_hdfs_path columns_file log_dir(end with '/') drop_cache
     *               spark master_hostname ordered_hdfs_path unordered_hdfs_path columns_file log_dir(end with '/') drop_cache
     *
     *               column_file is the query.column file generated by oil-layout.
     */
    @Override
    public void execute(String[] params)
    {
        if (params[0].equalsIgnoreCase("--help") || params[0].equalsIgnoreCase("help"))
        {
            log.error("[Usage] local ordered_hdfs_path unordered_hdfs_path columns_file log_dir(end with '/') drop_cache");
            log.error("[Usage] spark master_hostname ordered_hdfs_path unordered_hdfs_path columns_file log_dir(end with '/') drop_cache");
            return;
        }

        if (params[0].equalsIgnoreCase("local"))
        {
            String orderedPath = params[1];
            String unorderedPath = params[2];
            String columnFilePath = params[3];
            String log_dir = params[4];
            boolean dropCache = Boolean.parseBoolean(params[5]);
            Configuration conf = new Configuration();
            try
            {
                // get metadata
                FileStatus[] orderedStatuses = LocalEvaluator.getFileStatuses(orderedPath, conf);
                FileStatus[] unorderedStatuses = LocalEvaluator.getFileStatuses(unorderedPath, conf);
                ParquetMetadata[] orderedMetadatas = LocalEvaluator.getMetadatas(orderedStatuses, conf);
                ParquetMetadata[] unorderedMetadatas = LocalEvaluator.getMetadatas(unorderedStatuses, conf);

                BufferedReader reader = new BufferedReader(new FileReader(columnFilePath));
                BufferedWriter timeWriter = new BufferedWriter(new FileWriter(log_dir + "local_time"));
                BufferedWriter columnWriter = new BufferedWriter(new FileWriter(log_dir + "columns"));
                String columns = null;
                int i = 0;
                while ((columns = reader.readLine()) != null)
                {
                    // evaluate
                    // clear the caches and buffers
                    if (dropCache)
                    {
                        Runtime.getRuntime().exec(this.getClass().getResource("/drop_caches.sh").getFile());
                    }
                    LocalMetrics orderedMetrics = LocalEvaluator.execute(orderedStatuses, orderedMetadatas, columns.split(","), conf);
                    // clear the caches and buffers
                    if (dropCache)
                    {
                        Runtime.getRuntime().exec(this.getClass().getResource("/drop_caches.sh").getFile());
                    }
                    LocalMetrics unorderedMetrics = LocalEvaluator.execute(unorderedStatuses, unorderedMetadatas, columns.split(","), conf);

                    // log the results
                    timeWriter.write(i + "\t" + orderedMetrics.getTimeMillis() + "\t" + unorderedMetrics.getTimeMillis() + "\n");
                    timeWriter.flush();
                    columnWriter.write("[query " + i + "]\nordered:\n");
                    for (Column column : orderedMetrics.getColumns())
                    {
                        columnWriter.write(column.getIndex() + ", " + column.getName() + "\n");
                    }
                    columnWriter.write("\nunordered:\n");
                    for (Column column : unorderedMetrics.getColumns())
                    {
                        columnWriter.write(column.getIndex() + ", " + column.getName() + "\n");
                    }
                    columnWriter.write("\n\n");
                    columnWriter.flush();
                    ++i;
                }
                timeWriter.close();
                columnWriter.close();
                reader.close();
            } catch (IOException e)
            {
                ExceptionHandler.Instance().log(ExceptionType.ERROR, "evaluate local error", e);
            }
        }
        else if (params[0].equalsIgnoreCase("spark"))
        {
            String masterHostName = params[1];
            String orderedPath = params[2];
            String unorderedPath = params[3];
            String columnFilePath = params[4];
            String log_dir = params[5];
            boolean dropCache = Boolean.parseBoolean(params[6]);
            try
            {
                // get the column sizes
                MetaDataStat stat = new MetaDataStat(masterHostName, 9000, orderedPath.split("9000")[1]);
                System.out.println(masterHostName);
                int n = stat.getFieldNames().size();
                List<String> names = stat.getFieldNames();
                double[] sizes = stat.getAvgColumnChunkSize();
                Map<String, Double> nameSizeMap = new HashMap<String, Double>();
                for (int j = 0; j < n; ++j)
                {
                    nameSizeMap.put(names.get(j).toLowerCase(), sizes[j]);
                }

                // begin evaluate
                BufferedReader reader = new BufferedReader(new FileReader(columnFilePath));
                BufferedWriter timeWriter = new BufferedWriter(new FileWriter(log_dir + "spark_time"));
                String columns;
                int i = 0;
                while ((columns = reader.readLine()) != null)
                {
                    // get the smallest column as the order by column
                    String orderByColumn = null;
                    double size = Double.MAX_VALUE;

                    for (String name : columns.split(","))
                    {
                        if (name.toLowerCase().equals("market"))
                        {
                            orderByColumn = "market";
                            break;
                        }
                    }

                    if (orderByColumn == null)
                    {
                        for (String name : columns.split(","))
                        {
                            if (nameSizeMap.get(name.toLowerCase()) < size)
                            {
                                size = nameSizeMap.get(name.toLowerCase());
                                orderByColumn = name.toLowerCase();
                            }
                        }
                    }

                    // evaluate
                    // clear the caches and buffers
                    if (dropCache)
                    {
                        Runtime.getRuntime().exec(this.getClass().getResource("/drop_caches.sh").getFile());
                    }
                    StageMetrics orderedMetrics = SparkEvaluator.execute("ordered_" + i, masterHostName, orderedPath, columns, orderByColumn);
                    // clear the caches and buffers
                    if (dropCache)
                    {
                        Runtime.getRuntime().exec(this.getClass().getResource("/drop_caches.sh").getFile());
                    }
                    StageMetrics unorderedMetrics = SparkEvaluator.execute("unordered_" + i, masterHostName, unorderedPath, columns, orderByColumn);

                    // log the results
                    timeWriter.write(i + "\t" + orderedMetrics.getDuration() + "\t" + unorderedMetrics.getDuration() + "\n");
                    timeWriter.flush();

                    ++i;
                }
                timeWriter.close();
                reader.close();

            } catch (IOException e)
            {
                ExceptionHandler.Instance().log(ExceptionType.ERROR, "evaluate local i/o error", e);
            } catch (MetaDataException e)
            {
                ExceptionHandler.Instance().log(ExceptionType.ERROR, "evaluate local metadata error", e);
                e.printStackTrace();
            }
        }
    }
}

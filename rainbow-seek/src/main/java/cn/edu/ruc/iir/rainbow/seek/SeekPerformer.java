package cn.edu.ruc.iir.rainbow.seek;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by hank on 2015/2/9.
 */
public class SeekPerformer
{
    private static SeekPerformer instance = null;

    public static SeekPerformer Instance ()
    {
        if (instance == null)
        {
            instance = new SeekPerformer();
        }
        return instance;
    }

    private SeekPerformer() {}

    /**
     * @param seekCosts the array to return the seek time (in us) of each seek.
     * @param fileStatus the file on HDFS to perform seeks, this file should contain only one block.
     * @param numSeeks how number of seeks to perform
     * @param readLength the length to read for each seek, must be positive, can not be larger than seekDistance
     * @param seekDistance distance of each seek, in bytes
     * @param conf the HDFS configuration
     * @return the actual number of seeks been performed.
     */
    public int hdfsSeekTest (long[] seekCosts, final FileStatus fileStatus, final int numSeeks,
                             final int readLength, final long seekDistance, Configuration conf)
            throws IOException
    {
        if (seekCosts.length < numSeeks)
        {
            return -1;
        }
        FileSystem fs = FileSystem.get(fileStatus.getPath().toUri(), conf);
        FSDataInputStream in = fs.open(fileStatus.getPath());//in parquet's source code, the second param(buffer size) is also not given.
        long pos = 0;
        long fileLen = fileStatus.getLen();

        int size = 0;
        byte[] buffer = new byte[readLength];
        in.seek(pos);
        // read number of size bytes.
        while (size < readLength)
        {
            size += in.read(buffer, size, readLength-size);
        }
        pos += seekDistance;

        int i = 0;
        for (; i < numSeeks && pos < fileLen; ++i)
        {
            size = 0;
            long startNanoTime = System.nanoTime();
            in.seek(pos);
            while (size < readLength)
            {
                size += in.read(buffer, size, readLength-size);
            }
            long endNanoTime = System.nanoTime();
            seekCosts[i] = (endNanoTime - startNanoTime) / 1000;// us
            pos += seekDistance;
        }

        in.close();
        return i;
    }

    /**
     * @param seekCosts the array to return the seek time (in us) of each seek.
     * @param fileStatus the file on HDFS to perform seeks, this file should contain only one block.
     * @param numSeeks how number of seeks to perform
     * @param readLength the length to read for each seek, must be positive, can not be larger than seekDistance
     * @param seekDistance distance of each seek, in bytes
     * @param conf the HDFS configuration
     * @return the actual number of seeks been performed.
     */
    public int hdfsInversedSeekTest (long[] seekCosts, final FileStatus fileStatus,
                                            final int numSeeks, final int readLength,
                                            final long seekDistance, Configuration conf)
            throws IOException
    {
        if (seekCosts.length < numSeeks)
        {
            return -1;
        }
        FileSystem fs = FileSystem.get(fileStatus.getPath().toUri(), conf);
        FSDataInputStream in = fs.open(fileStatus.getPath());//in parquet's source code, the second param(buffer size) is also not given.
        long pos = fileStatus.getLen()-1-readLength;

        int size = 0;
        byte[] buffer = new byte[readLength];
        in.seek(pos);
        while (size < readLength)
        {
            size += in.read(buffer, size, readLength-size);
        }
        pos -= seekDistance;

        int i = 0;
        for (; i < numSeeks && pos >= 0; ++i)
        {
            size = 0;
            long startNanoTime = System.nanoTime();
            in.seek(pos);
            while (size < readLength)
            {
                size += in.read(buffer, size, readLength-size);
            }
            long endNanoTime = System.nanoTime();
            seekCosts[i] = (endNanoTime - startNanoTime) / 1000;// us
            pos -= seekDistance;
        }

        in.close();
        return i;
    }

    /**
     *
     * @param seekCosts
     * @param file
     * @param initPos
     * @param seekTimes
     * @param readSize
     * @param seekDistance
     * @return
     * @throws IOException
     */
    public int localSeekTest (long[] seekCosts, final File file, final long initPos, final int seekTimes, final int readSize, final long seekDistance) throws IOException
    {
        if (seekCosts.length < seekTimes)
        {
            return -1;
        }
        long fileLen = file.length();
        long pos = initPos;
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        int size = 0;
        byte[] buffer = new byte[readSize];

        raf.seek(pos);
        while (size < readSize)
        {
            size += raf.read(buffer, size, readSize-size);
        }
        pos += seekDistance;

        int i = 0;
        for (; i < seekTimes && pos < fileLen; ++i)
        {
            size = 0;
            long startNanoTime = System.nanoTime();
            raf.seek(pos);
            while (size < readSize)
            {
                size += raf.read(buffer, size, readSize-size);
            }
            long endNanoTime = System.nanoTime();
            seekCosts[i] = (endNanoTime-startNanoTime) / 1000;
            pos += seekDistance;
        }
        raf.close();

        return i;
    }

    /**
     *
     * @param seekCosts
     * @param seekTimes must be positive
     * @param path
     * @return the middle seek cost
     * @throws IOException
     */
    public long logSeekCost (long[] seekCosts, int seekTimes, String path) throws IOException
    {
        BufferedWriter writer = new BufferedWriter(new FileWriter(path));
        //double sum = 0;
        List<Long> seekCostList = new ArrayList<Long>();
        for (int i = 0; i < seekTimes; ++i)
        {
            //sum += seekCosts[i];
            seekCostList.add(seekCosts[i]);
            writer.write(seekCosts[i] + "\n");
        }
        writer.flush();
        writer.close();

        /*
        //取中位数
        Collections.sort(seekCostList);
        long midSeekCost = seekCostList.get(seekTimes/2);
        if (seekTimes % 2 == 0)
        {
            midSeekCost = (midSeekCost + seekCostList.get(seekTimes/2-1)) / 2;
        }
        */
        //取均值（期望）
        long midSeekCost = 0;
        for (long cost : seekCostList)
        {
            midSeekCost += cost;
        }
        return midSeekCost/seekTimes;
    }
}

package jgi;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;

import kmer.AbstractKmerTable;
import kmer.HashArray;
import kmer.HashForest;
import kmer.KmerTable;

import stream.ConcurrentGenericReadInputStream;
import stream.ConcurrentReadStreamInterface;
import stream.FASTQ;
import stream.FastaReadInputStream;
import stream.Read;
import align2.ListNum;
import align2.Shared;
import align2.Tools;
import dna.Parser;
import dna.Timer;
import fileIO.ByteFile;
import fileIO.ByteFile1;
import fileIO.ByteFile2;
import fileIO.FileFormat;
import fileIO.ReadWrite;
import fileIO.TextStreamWriter;

/**
 * @author Brian Bushnell
 * @date Mar 24, 2014
 *
 */
public class CalcUniqueness {
	
	
	/*--------------------------------------------------------------*/
	/*----------------        Initialization        ----------------*/
	/*--------------------------------------------------------------*/

	
	public static void main(String[] args){
		Timer t=new Timer();
		t.start();
		CalcUniqueness rr=new CalcUniqueness(args);
		rr.process(t);
	}
	
	public CalcUniqueness(String[] args){
		
		if(Parser.parseHelp(args)){
			printOptions();
			System.exit(0);
		}
		
		outstream.println("Executing "+getClass().getName()+" "+Arrays.toString(args)+"\n");
		
		boolean setInterleaved=false; //Whether it was explicitly set.

		FastaReadInputStream.SPLIT_READS=false;
		stream.FastaReadInputStream.MIN_READ_LEN=1;
		Shared.READ_BUFFER_LENGTH=Tools.min(200, Shared.READ_BUFFER_LENGTH);
		Shared.READ_BUFFER_NUM_BUFFERS=Tools.min(8, Shared.READ_BUFFER_NUM_BUFFERS);
		ReadWrite.USE_UNPIGZ=true;
		ReadWrite.MAX_ZIP_THREADS=8;
		ReadWrite.ZIP_THREAD_DIVISOR=2;
		int k_=20;
		
		Parser parser=new Parser();
		for(int i=0; i<args.length; i++){
			String arg=args[i];
			String[] split=arg.split("=");
			String a=split[0].toLowerCase();
			String b=split.length>1 ? split[1] : null;
			while(a.startsWith("-")){a=a.substring(1);} //In case people use hyphens

			if(parser.parse(arg, a, b)){
				//do nothing
			}else if(a.equals("null") || a.equals(parser.in2)){
				// do nothing
			}else if(a.equals("verbose")){
				verbose=Tools.parseBoolean(b);
				ByteFile1.verbose=verbose;
				ByteFile2.verbose=verbose;
				stream.FastaReadInputStream.verbose=verbose;
				stream.FastqReadInputStream.verbose=verbose;
				ConcurrentGenericReadInputStream.verbose=verbose;
				ReadWrite.verbose=verbose;
			}else if(a.equals("cumulative")){
				cumulative=Tools.parseBoolean(b);
			}else if(a.equals("percent") || a.equals("percents")){
				showPercents=Tools.parseBoolean(b);
			}else if(a.equals("count") || a.equals("counts")){
				showCounts=Tools.parseBoolean(b);
			}else if(a.equals("k")){
				k_=Integer.parseInt(b);
			}else if(a.equals("bin") || a.equals("interval")){
				interval=Integer.parseInt(b);
			}else if(parser.in1==null && i==0 && !arg.contains("=") && (arg.toLowerCase().startsWith("stdin") || new File(arg).exists())){
				parser.in1=arg;
				if(arg.indexOf('#')>-1 && !new File(arg).exists()){
					parser.in1=b.replace("#", "1");
					parser.in2=b.replace("#", "2");
				}
			}else if(parser.out1==null && i==1 && !arg.contains("=")){
				parser.out1=arg;
			}else{
//				System.err.println("Unknown parameter "+args[i]);
//				assert(false) : "Unknown parameter "+args[i];
				throw new RuntimeException("Unknown parameter "+args[i]);
			}
		}
		
		{//Download parser fields
			
			maxReads=parser.maxReads;	
			samplerate=parser.samplerate;
			sampleseed=parser.sampleseed;

			overwrite=parser.overwrite;
			append=parser.append;
			testsize=parser.testsize;
			
			setInterleaved=parser.setInterleaved;
			
			in1=parser.in1;
			in2=parser.in2;

			out=parser.out1;
			
			extin=parser.extin;
			extout=parser.extout;
		}
		setSampleSeed(-1);
		
		k=k_;
		k2=k-1;
		
		if(in1!=null && in2==null && in1.indexOf('#')>-1 && !new File(in1).exists()){
			in2=in1.replace("#", "2");
			in1=in1.replace("#", "1");
		}
		if(in2!=null){
			if(FASTQ.FORCE_INTERLEAVED){System.err.println("Reset INTERLEAVED to false because paired input files were specified.");}
			FASTQ.FORCE_INTERLEAVED=FASTQ.TEST_INTERLEAVED=false;
		}
		
		assert(FastaReadInputStream.settingsOK());
		
		if(in1==null){
			printOptions();
			throw new RuntimeException("Error - at least one input file is required.");
		}
		if(!ByteFile.FORCE_MODE_BF1 && !ByteFile.FORCE_MODE_BF2 && Shared.THREADS>2){
			ByteFile.FORCE_MODE_BF2=true;
		}
		
		if(out==null){
			out="stdout.txt";
		}
		
		if(!setInterleaved){
			assert(in1!=null && out!=null) : "\nin1="+in1+"\nin2="+in2+"\nout="+out+"\n";
			if(in2!=null){ //If there are 2 input streams.
				FASTQ.FORCE_INTERLEAVED=FASTQ.TEST_INTERLEAVED=false;
				outstream.println("Set INTERLEAVED to "+FASTQ.FORCE_INTERLEAVED);
			}
		}
		
		{
			byte qin=Parser.qin, qout=Parser.qout;
			if(qin!=-1 && qout!=-1){
				FASTQ.ASCII_OFFSET=qin;
				FASTQ.DETECT_QUALITY=false;
			}else if(qin!=-1){
				FASTQ.ASCII_OFFSET=qin;
				FASTQ.DETECT_QUALITY=false;
			}
		}
		
		if(!Tools.testOutputFiles(overwrite, append, false, out)){
			throw new RuntimeException("\n\noverwrite="+overwrite+"; Can't write to output file "+out+"\n");
		}
		
		ffout=FileFormat.testOutput(out, FileFormat.TEXT, extout, false, overwrite, append, false);

		ffin1=FileFormat.testInput(in1, FileFormat.FASTQ, extin, true, true);
		ffin2=FileFormat.testInput(in2, FileFormat.FASTQ, extin, true, true);

		keySets=new AbstractKmerTable[WAYS];

		//Initialize tables
		for(int j=0; j<WAYS; j++){
			if(useForest){
				keySets[j]=new HashForest(initialSize, true);
			}else if(useTable){
				keySets[j]=new KmerTable(initialSize, true);
			}else if(useArray){
				keySets[j]=new HashArray(initialSize, true);
			}else{
				throw new RuntimeException("Must use forest, table, or array data structure.");
			}
		}
		
	}
	
	/*--------------------------------------------------------------*/
	/*----------------         Inner Class          ----------------*/
	/*--------------------------------------------------------------*/
	
	private class Counter{
		
		Counter(int mask_){
			mask=mask_;
		}
		
		void increment(final long kmer){
			AbstractKmerTable table=keySets[(int)(kmer%WAYS)];
			int count=table.getCount(kmer);
			if(count<1){
				table.set(kmer, mask);
				misses++;
				cmisses++;
			}else if((count&mask)==0){
				table.set(kmer, count|mask);
				misses++;
				cmisses++;
			}else{
				hits++;
				chits++;
			}
		}
		
		void reset(){
			hits=misses=0;
		}
		
		double percent(){
			return misses()*100.0/(hits()+misses());
		}
		
		String percentS(){
			return String.format("%.3f",percent());
		}

		long hits(){return cumulative ? chits : hits;}
		long misses(){return cumulative ? cmisses : misses;}
		
		final int mask;
		
		/** Per-interval hash hits */
		long hits=0;
		/** Per-interval hash misses */
		long misses=0;
		
		/** Cumulative hash hits */
		long chits=0;
		/** Cumulative hash misses */
		long cmisses=0;
		
	}
	
	/*--------------------------------------------------------------*/
	/*----------------        Primary Method        ----------------*/
	/*--------------------------------------------------------------*/
	
	void process(Timer t){
		
		final ConcurrentReadStreamInterface cris;
		final Thread cristhread;
		{
			cris=ConcurrentGenericReadInputStream.getReadInputStream(maxReads, colorspace, false, ffin1, ffin2, null, null);
			cris.setSampleRate(samplerate, sampleseed);
			if(verbose){System.err.println("Started cris");}
			cristhread=new Thread(cris);
			cristhread.start();
		}
		final boolean paired=cris.paired();
		if(verbose){System.err.println("Input is "+(paired ? "paired" : "unpaired"));}

		TextStreamWriter tsw=null;
		if(out!=null){
			tsw=new TextStreamWriter(ffout);
			tsw.start();
			tsw.print("#count");
			if(showPercents){
				tsw.print("\tfirst\trand");
				if(paired){tsw.print("\tr1_first\tr1_rand\tr2_first\tr2_rand\tpair");}
			}
			if(showCounts){
				tsw.print("\tfirst_cnt\trand_cnt");
				if(paired){tsw.print("\tr1_first_cnt\tr1_rand_cnt\tr2_first_cnt\tr2_rand_cnt\tpair_cnt");}
			}
			tsw.print("\n");
		}
		
		//Counters for overall data statistics
		long pairsProcessed=0;
		long readsProcessed=0;
		long basesProcessed=0;
		
		//Counters for hashtable hits and misses of different kmers
		final Counter r1CounterFirst=new Counter(1);
		final Counter r1CounterRand=new Counter(2);
		final Counter r2CounterFirst=new Counter(4);
		final Counter r2CounterRand=new Counter(8);
		final Counter pairCounter=new Counter(16);

		final Counter bothCounterFirst=new Counter(32);
		final Counter bothCounterRand=new Counter(64);
		
		//Counter for display intervals
		long remaining=interval;
		
		final StringBuilder sb=new StringBuilder(1024);
		
		{
			//Fetch initial list
			ListNum<Read> ln=cris.nextList();
			ArrayList<Read> reads=(ln!=null ? ln.list : null);
			
			if(reads!=null && !reads.isEmpty()){
				Read r=reads.get(0);
				assert((ffin1==null || ffin1.samOrBam()) || (r.mate!=null)==cris.paired());
			}
			
			/* Process 1 list of reads per loop iteration */
			while(reads!=null && reads.size()>0){
				
				/* Process 1 read per loop iteration */
				for(Read r1 : reads){
					final Read r2=r1.mate;
					final byte[] bases1=(r1==null ? null : r1.bases);
					final byte[] bases2=(r2==null ? null : r2.bases);
					final int length1=(bases1==null ? 0 : bases1.length);
					final int length2=(bases2==null ? 0 : bases2.length);
					
					pairsProcessed++;
					
					/* Process read 1 */
					if(r1!=null){
						
						readsProcessed++;
						basesProcessed+=length1;
						
						if(length1>=k){
							{//First kmer
								final long kmer=toKmer(bases1, 0, k);
								r1CounterFirst.increment(kmer);
								bothCounterFirst.increment(kmer);
							}
							{//Random kmer
								final long kmer=toKmer(bases1, randy.nextInt(length1-k2), k);
								r1CounterRand.increment(kmer);
								bothCounterRand.increment(kmer);
							}
						}
					}
					
					/* Process read 2 */
					if(r2!=null){
						
						readsProcessed++;
						basesProcessed+=length2;
						
						if(length2>=k){
							{//First kmer
								final long kmer=toKmer(bases2, 0, k);
								r2CounterFirst.increment(kmer);
								bothCounterFirst.increment(kmer);
							}
							{//Random kmer
								final long kmer=toKmer(bases2, randy.nextInt(length2-k2), k);
								r2CounterRand.increment(kmer);
								bothCounterRand.increment(kmer);
							}
						}
					}
					
					/* Process pair */
					if(r1!=null && r2!=null){
						
						if(length1>k+OFFSET && length2>k+OFFSET){
							final long kmer1=toKmer(bases1, OFFSET, k);
							final long kmer2=toKmer(bases2, OFFSET, k);
							final long kmer=(~((-1L)>>2))|((kmer1<<(2*(31-k)))^(kmer2));
							assert(kmer>=0) : k+", "+kmer1+", "+kmer2+", "+kmer;
							{//Pair kmer
								pairCounter.increment(kmer);
							}
						}
					}
					
					remaining--;
					if(remaining<=0){
						
						//Display data for the last interval
						sb.append(pairsProcessed);
						
						if(showPercents){
							sb.append('\t');
							sb.append(bothCounterFirst.percentS());
							sb.append('\t');
							sb.append(bothCounterRand.percentS());
							if(paired){
								sb.append('\t');
								sb.append(r1CounterFirst.percentS());
								sb.append('\t');
								sb.append(r1CounterRand.percentS());
								sb.append('\t');
								sb.append(r2CounterFirst.percentS());
								sb.append('\t');
								sb.append(r2CounterRand.percentS());
								sb.append('\t');
								sb.append(pairCounter.percentS());
							}
						}
						
						if(showCounts){
							sb.append('\t');
							sb.append(bothCounterFirst.misses());
							sb.append('\t');
							sb.append(bothCounterRand.misses());
							if(paired){
								sb.append('\t');
								sb.append(r1CounterFirst.misses());
								sb.append('\t');
								sb.append(r1CounterRand.misses());
								sb.append('\t');
								sb.append(r2CounterFirst.misses());
								sb.append('\t');
								sb.append(r2CounterRand.misses());
								sb.append('\t');
								sb.append(pairCounter.misses());
							}
						}
						
						sb.append('\n');
						tsw.print(sb.toString());
						
						//Reset things
						sb.setLength(0);
						remaining=interval;

						bothCounterFirst.reset();
						bothCounterRand.reset();
						r1CounterFirst.reset();
						r1CounterRand.reset();
						r2CounterFirst.reset();
						r2CounterRand.reset();
						pairCounter.reset();
					}
				}
				
				//Fetch a new list
				cris.returnList(ln, ln.list.isEmpty());
				ln=cris.nextList();
				reads=(ln!=null ? ln.list : null);
			}
			if(ln!=null){//Return final list
				cris.returnList(ln, ln.list==null || ln.list.isEmpty());
			}
		}
		
		//Close things
		errorState|=ReadWrite.closeStream(cris);
		tsw.poisonAndWait();
		errorState|=tsw.errorState;
		
		t.stop();

		//Calculate and display statistics
		double rpnano=readsProcessed/(double)(t.elapsed);
		double bpnano=basesProcessed/(double)(t.elapsed);

		String rpstring=(readsProcessed<100000 ? ""+readsProcessed : readsProcessed<100000000 ? (readsProcessed/1000)+"k" : (readsProcessed/1000000)+"m");
		String bpstring=(basesProcessed<100000 ? ""+basesProcessed : basesProcessed<100000000 ? (basesProcessed/1000)+"k" : (basesProcessed/1000000)+"m");

		while(rpstring.length()<8){rpstring=" "+rpstring;}
		while(bpstring.length()<8){bpstring=" "+bpstring;}
		
		outstream.println("\nTime:                         \t"+t);
		outstream.println("Reads Processed:    "+rpstring+" \t"+String.format("%.2fk reads/sec", rpnano*1000000));
		outstream.println("Bases Processed:    "+bpstring+" \t"+String.format("%.2fm bases/sec", bpnano*1000));
		if(testsize){
			long bytesProcessed=(new File(in1).length()+(in2==null ? 0 : new File(in2).length()));
			double xpnano=bytesProcessed/(double)(t.elapsed);
			String xpstring=(bytesProcessed<100000 ? ""+bytesProcessed : bytesProcessed<100000000 ? (bytesProcessed/1000)+"k" : (bytesProcessed/1000000)+"m");
			while(xpstring.length()<8){xpstring=" "+xpstring;}
			outstream.println("Bytes Processed:    "+xpstring+" \t"+String.format("%.2fm bytes/sec", xpnano*1000));
		}
		
		if(errorState){
			throw new RuntimeException("CalcUniqueness terminated in an error state; the output may be corrupt.");
		}
	}
	
	/*--------------------------------------------------------------*/
	/*----------------        Helper Methods        ----------------*/
	/*--------------------------------------------------------------*/
	
	/**
	 * Generate a kmer from specified start location
	 * @param bases
	 * @param start
	 * @param klen kmer length
	 * @return kmer
	 */
	private final long toKmer(final byte[] bases, final int start, final int klen){
		final int stop=start+klen;
		assert(stop<=bases.length);
		long kmer=0;
		
		for(int i=start; i<stop; i++){
			final byte b=bases[i];
			final long x=Dedupe.baseToNumber[b];
			kmer=((kmer<<2)|x);
		}
		return kmer;
	}
	
	/*--------------------------------------------------------------*/
	
	private void printOptions(){
		outstream.println("Syntax:\n");
		outstream.println("java -ea -Xmx512m -cp <path> jgi.CalcUniqueness in=<infile> in2=<infile2> out=<outfile>");
		outstream.println("\nin2 and out2 are optional.  \nIf input is paired and there is only one output file, it will be written interleaved.\n");
		outstream.println("Other parameters and their defaults:\n");
		outstream.println("overwrite=false  \tOverwrites files that already exist");
		outstream.println("interleaved=auto \tDetermines whether input file is considered interleaved");
		outstream.println("bin=25000        \t(interval) Number of reads per data point");
		outstream.println("qin=auto         \tASCII offset for input quality.  May be set to 33 (Sanger), 64 (Illumina), or auto");
		outstream.println("k=20             \tKmer length");
	}
	
	
	public void setSampleSeed(long seed){
		
		//Note: ThreadLocalRandom does not allow seed to be set.
		randy=java.util.concurrent.ThreadLocalRandom.current();
		if(seed>-1){
//			randy.setSeed(seed);
		}else{
//			randy.setSeed(System.nanoTime());
		}
	}
	
	/*--------------------------------------------------------------*/
	/*----------------            Fields            ----------------*/
	/*--------------------------------------------------------------*/
	
	private String in1=null;
	private String in2=null;
	
	private String out=null;
	
	private String extin=null;
	private String extout=null;
	
	/*--------------------------------------------------------------*/
	
	private long maxReads=-1;
	private float samplerate=1f;
	private long sampleseed=-1;

	private long interval=25000;
	private boolean cumulative=false;
	private boolean showPercents=true;
	private boolean showCounts=false;

	private final int k, k2;
	private static final int WAYS=31;
	private static final int OFFSET=10;
	
	/** Initial size of data structures */
	private int initialSize=512000;
	
	/** Hold kmers.  A kmer X such that X%WAYS=Y will be stored in keySets[Y] */
	private final AbstractKmerTable[] keySets;
	
	/*--------------------------------------------------------------*/
	
	private final FileFormat ffin1;
	private final FileFormat ffin2;

	private final FileFormat ffout;
	
	
	/*--------------------------------------------------------------*/
	
	private PrintStream outstream=System.err;
	public static boolean verbose=false;
	public boolean errorState=false;
	private boolean overwrite=false;
	private boolean append=false;
	private boolean colorspace=false;
	private boolean testsize=false;
	
	private static final boolean useForest=false, useTable=false, useArray=true;
	
	private java.util.concurrent.ThreadLocalRandom randy;
	
}
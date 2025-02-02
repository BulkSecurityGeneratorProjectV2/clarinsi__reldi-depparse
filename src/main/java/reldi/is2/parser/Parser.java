package is2.parser;


import is2.data.Cluster;
import is2.data.DataF;
import is2.data.DataFES;
import is2.data.F2SF;
import is2.data.FV;
import is2.data.Instances;
import is2.data.Long2Int;
import is2.data.Long2IntInterface;
import is2.data.Parse;
import is2.data.PipeGen;
import is2.data.SentenceData09;
import is2.io.CONLLReader09;
import is2.io.CONLLWriter09;
import is2.tools.Retrainable;
import is2.tools.Tool;
import is2.util.DB;
import is2.util.OptionsSuper;
import is2.util.ParserEvaluator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;


import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Parser implements Tool, Retrainable {

	// output evaluation info 
	private static final boolean MAX_INFO = true;

	public static int THREADS =4;

	public Long2IntInterface l2i;
	public ParametersFloat params;
	public Pipe pipe;
	public OptionsSuper options;


	// keep some of the parsing information for later evaluation
	public Instances is;
	DataFES d2;
	public Parse d=	null;

	/**
	 * Initialize the parser
	 * @param options
	 */
	public Parser (OptionsSuper options) {

		this.options=options;
		pipe = new Pipe(options);

		params = new ParametersFloat(0);  

		// load the model
		try {
			readModel(options, pipe, params);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}


	/**
	 * @param modelFileName The file name of the parsing model
	 */
	public Parser(String modelFileName) {
		this(new Options(new String[]{"-model",modelFileName}));
	}


	/**
	 * 
	 */
	public Parser() {
		// TODO Auto-generated constructor stub
	}

	public static void main (String[] args) throws Exception
	{
		long start = System.currentTimeMillis();
		OptionsSuper options = new Options(args);

		Runtime runtime = Runtime.getRuntime();
		THREADS = runtime.availableProcessors();
		if (options.cores < THREADS && options.cores > 0) {
			THREADS =options.cores;
		}
		DB.println("Found " + runtime.availableProcessors()+" cores use "+THREADS);

		Parser p = new Parser(options);
		DB.println("label only? " + options.label);
		p.out(options, p.pipe, p.params, !MAX_INFO, options.label, "");

		long end = System.currentTimeMillis();
		System.out.println("used time "+((float)((end-start)/100)/10));

		Decoder.executerService.shutdown();		
		Pipe.executerService.shutdown();
		System.out.println("end.");
	}

	/**
	 * Read the models and mapping
	 * @param options
	 * @param pipe
	 * @param params
	 * @throws IOException
	 */
	public   void readModel(OptionsSuper options, Pipe pipe, Parameters params) throws IOException {


		DB.println("Reading data started");

		// prepare zipped reader
		ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(options.modelName)));
		zis.getNextEntry();
		DataInputStream dis = new DataInputStream(new BufferedInputStream(zis));

		pipe.mf.read(dis);

		pipe.cl = new Cluster(dis);

		params.read(dis);
		this.l2i = new Long2Int(params.size());
		DB.println("parsing -- li size "+l2i.size());


		pipe.extractor = new Extractor[THREADS];

		boolean stack = dis.readBoolean();

		options.featureCreation=dis.readInt();

		for (int t=0;t<THREADS;t++) pipe.extractor[t]=new Extractor(l2i, stack,options.featureCreation);
		DB.println("Stacking "+stack);

		Extractor.initFeatures();
		Extractor.initStat(options.featureCreation);


		for (int t=0;t<THREADS;t++)  pipe.extractor[t].init();

		Edges.read(dis);

		options.decodeProjective = dis.readBoolean();

		Extractor.maxForm = dis.readInt();

		boolean foundInfo =false;
		try {
			String info =null;
			int icnt = dis.readInt();
			for(int i=0;i<icnt;i++) {
				info = dis.readUTF();
				System.out.println(info);
			}
		} catch (Exception e) {
			if (!foundInfo) System.out.println("no info about training");
		}


		dis.close();

		DB.println("Reading data finnished");

		Decoder.NON_PROJECTIVITY_THRESHOLD =(float)options.decodeTH;

		Extractor.initStat(options.featureCreation);

	}

	/**
	 * Do the parsing job
	 * 
	 * @param options
	 * @param pipe
	 * @param params
	 * @throws IOException
	 */
	public StringBuilder out (OptionsSuper options, Pipe pipe, ParametersFloat params, boolean maxInfo, boolean labelOnly, String input)
			throws Exception {

		long start = System.currentTimeMillis();

		InputStream is = new ByteArrayInputStream(input.getBytes());
		CONLLReader09 depReader = new CONLLReader09(new BufferedReader(new InputStreamReader(is)));
		//CONLLWriter09 depWriter = new CONLLWriter09(new BufferedWriter(new OutputStreamWriter(System.out)));

		int cnt = 0;
		int del=0;
		long last = System.currentTimeMillis();

		if (maxInfo) System.out.println("\nParsing Information ");
		if (maxInfo) System.out.println("------------------- ");

		if (maxInfo && !options.decodeProjective) System.out.println(""+Decoder.getInfo());
		StringBuilder str = new StringBuilder();
		boolean stmnt = true;
		while(stmnt) {
			SentenceData09 instance = depReader.getNext();
			if (instance == null) {
				stmnt = false;
				continue;
			};
			cnt++;

			SentenceData09 i09 = this.parse(instance,params, labelOnly,options);

			str.append(i09);
		//	depWriter.write(i09);
		//	depWriter.flush();
		}
		return str;
	}

	/**
	 * Parse a single sentence
	 * 
	 * @param instance
	 * @param params
	 * @param labelOnly
	 * @param options
	 * @return
	 */
	public SentenceData09 parse (SentenceData09 instance, ParametersFloat params, boolean labelOnly, OptionsSuper options)   {

		String[] types = new String[pipe.mf.getFeatureCounter().get(PipeGen.REL)];
		for (Entry<String, Integer> e : MFO.getFeatureSet().get(PipeGen.REL).entrySet())  	types[e.getValue()] = e.getKey();

				is = new Instances();
				is.init(1, new MFO(),options.formatTask);
				new CONLLReader09().insert(is, instance); 

				// use for the training ppos

				SentenceData09 i09 = new SentenceData09(instance);
				i09.createSemantic(instance);

				if (labelOnly) {
					F2SF f2s =params.getFV();

					// repair pheads

					is.pheads[0]= is.heads[0];

					for(int l=0;l<is.pheads[0].length;l++) {
						if (is.pheads[0][l]<0)is.pheads[0][l]=0;
					}

					short[] labels = pipe.extractor[0].searchLabel(is, 0, is.pposs[0], is.forms[0], is.plemmas[0], is.pheads[0], is.plabels[0], is.feats[0], pipe.cl, f2s);

					for(int j = 0; j < instance.forms.length-1; j++) {
						i09.plabels[j] = types[labels[j+1]];
						i09.pheads[j] = is.pheads[0][j+1];
					}
					return i09;
				}

				if (options.maxLength > instance.length() && options.minLength <= instance.length()) {
					try {
						//			System.out.println("prs "+instance.forms[0]);
						//			System.out.println("prs "+instance.toString());
						d2 = pipe.fillVector(params.getFV(), is,0,null,pipe.cl);//cnt-1
						d =Decoder.decode(is.pposs[0],d2,options.decodeProjective, !Decoder.TRAINING); //cnt-1

					}catch (Exception e) {		
						e.printStackTrace();
					}

					for(int j = 0; j < instance.forms.length-1; j++) {
						i09.plabels[j] = types[d.labels[j+1]];
						i09.pheads[j] = d.heads[j+1];
					}
				}
				return i09;

	}

	is2.io.CONLLReader09 reader = new is2.io.CONLLReader09(true);
	/* (non-Javadoc)
	 * @see is2.tools.Tool#apply(is2.data.SentenceData09)
	 */
	@Override
	public SentenceData09 apply(SentenceData09 snt09) {

		SentenceData09 it = new SentenceData09();
		it.createWithRoot(snt09);

		SentenceData09 out=null;
		try {


			//		for(int k=0;k<it.length();k++) {
			//			it.forms[k] = reader.normalize(it.forms[k]);
			//			it.plemmas[k] = reader.normalize(it.plemmas[k]);
			//		}

			out = parse(it,this.params,false,options);


		} catch(Exception e) {
			e.printStackTrace();
		}

		Decoder.executerService.shutdown();		
		Pipe.executerService.shutdown();

		return out;
	}

	/**
	 * Get the edge scores of the last parse.
	 * @return the scores
	 */
	public float[] getInfo() {
		float[] scores = new float[is.length(0)];
		Extractor.encode3(is.pposs[0], d.heads, d.labels, d2,scores);

		return scores;
	}


	/**
	 * Write the parsing model
	 * 
	 * @param options
	 * @param params
	 * @param extension
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private void writeModell(OptionsSuper options, ParametersFloat params, String extension, Cluster cs) throws FileNotFoundException, IOException {

		String name = extension==null?options.modelName:options.modelName+extension;
		//		System.out.println("Writting model: "+name);
		ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(name)));
		zos.putNextEntry(new ZipEntry("data")); 
		DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(zos));     

		MFO.writeData(dos);
		cs.write(dos);

		params.write(dos);

		dos.writeBoolean(options.stack);
		dos.writeInt(options.featureCreation);


		Edges.write(dos);

		dos.writeBoolean(options.decodeProjective);

		dos.writeInt(Extractor.maxForm);

		dos.writeInt(5);  // Info count
		dos.writeUTF("Used parser   "+Parser.class.toString());
		dos.writeUTF("Creation date "+(new SimpleDateFormat("yyyy.MM.dd HH:mm:ss")).format(new Date()));
		dos.writeUTF("Training data "+options.trainfile);
		dos.writeUTF("Iterations    "+options.numIters+" Used sentences "+options.count);
		dos.writeUTF("Cluster       "+options.clusterFile);

		dos.flush();
		dos.close();
	}


	@Override
	public boolean retrain(SentenceData09 sentence, float upd, int iterations) {

		params.total = params.parameters;

		boolean done=false;

		for(int k=0;k<iterations;k++) {
			try {
				// create the data structure
				DataFES data = new DataFES(sentence.length(), pipe.mf.getFeatureCounter().get(PipeGen.REL).shortValue());


				Instances is = new Instances();
				is.m_encoder =pipe.mf;



				is.init(1, pipe.mf,options.formatTask);
				new CONLLReader09().insert(is, sentence); 

				//				String list[] = ((MFO)is.m_encoder).reverse(((MFO)is.m_encoder).getFeatureSet().get(Pipe.POS));
				//				for(String s :list) {
				//					System.out.println(s+" ");
				//				}

				//				for(int i=0;i<is.length(0);i++) {

				//					System.out.printf("%d\t %d\t %d \n",i,is.forms[0][i],is.pposs[0][i] );
				//					System.out.printf("%s\t form:%s pos:%s\n",i,sentence.forms[i],sentence.ppos[i]);

				//				}

				SentenceData09 i09 = new SentenceData09(sentence);
				i09.createSemantic(sentence);



				// create the weights 
				data = pipe.fillVector((F2SF)params.getFV(), is, 0, data, pipe.cl);

				short[] pos = is.pposs[0];

				// parse the sentence
				Parse d = Decoder.decode(pos,  data, options.decodeProjective, Decoder.TRAINING);

				// training successful?
				double e= pipe.errors(is, 0 ,d);
				//			System.out.println("errors "+e);
				if (e==0) {


					done= true;
					break;
				}

				// update the weight vector
				FV pred = new FV();
				pipe.extractor[0].encodeCat(is,0,pos,is.forms[0],is.plemmas[0],d.heads, d.labels, is.feats[0],pipe.cl, pred);

				params.getFV();

				FV act = new FV();
				pipe.extractor[0].encodeCat(is,0,pos,is.forms[0],is.plemmas[0],is.heads[0], is.labels[0], is.feats[0],pipe.cl, act);

				params.update(act, pred, is, 0, d, upd,e);

				if (upd >0)upd--;

			} catch(Exception e) {
				e.printStackTrace();
			}
		}
		Decoder.executerService.shutdown();		
		Pipe.executerService.shutdown();


		return done;
	}


	@Override
	public boolean retrain(SentenceData09 sentence, float upd, int iterations, boolean print) {
		// TODO Auto-generated method stub
		return  retrain( sentence,  upd,  iterations);
	}
}

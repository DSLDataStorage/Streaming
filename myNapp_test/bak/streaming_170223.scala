import org.apache.spark._
import org.apache.spark.streaming._
import org.apache.spark.HashPartitioner
import org.apache.spark.RangePartitioner
import org.apache.spark.streaming.kafka._
import org.apache.spark.storage.StorageLevel

import com.mongodb.casbah.Imports.MongoDBObject
import com.mongodb.casbah.Imports.MongoClient
import com.mongodb.casbah.Imports.DBObject

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import scala.util.control.Breaks._

import akka.actor._

object Streaming{	

	def main(args: Array[String]){
		val conf = new SparkConf().setMaster("mesos://192.168.0.241:5050")		
		val sc = new SparkContext(conf)
		
		var isFirst = 1 // if 1, first
		var flag = 1
		
		var printON: Boolean = false
		var printTime: Boolean = false
		var streamingIteration = 1		

		var isCogroup = true
		var enableCacheCleaningFunction = true
		var cacheCleaningVersion = 'b'

		if(args(0) == "cog"){
			isCogroup = true
		}else if(args(0) == "js"){
			isCogroup = false
		}

		var isREx = false
		var missedCache = false
		var partition_num = args(1).toInt
		var syncMissedTmp = false
		var syncMissedRDD = false
		
		var cachedPRDD: org.apache.spark.rdd.RDD[(Int, String)] = null
		var cacheTmp: org.apache.spark.rdd.RDD[(Int, String)] = null
		var LRUKeyTmp: org.apache.spark.rdd.RDD[(Int, Int)] = null
		var missedPRDD: org.apache.spark.rdd.RDD[(Int, String)] = null
		var DB_PRDD: org.apache.spark.rdd.RDD[(Int, String)] = null
		var LRUKey: org.apache.spark.rdd.RDD[(Int, Int)] = null
		var LRUKey2: org.apache.spark.rdd.RDD[(Int, Int)] = null
		//var LRUKey: Array[String] = null
		var joinedPRDD_hit: org.apache.spark.rdd.RDD[(Int, (String, String))] = null
		var joinedPRDD_missed: org.apache.spark.rdd.RDD[(Int, (String, String))] = null
		var cog: org.apache.spark.rdd.RDD[(Int, (Iterable[String], Iterable[String]))] = null
		var loj: org.apache.spark.rdd.RDD[(Int, (String, Option[String]))] = null
		var missedTmp: org.apache.spark.rdd.RDD[(Int, (Iterable[String], Iterable[String]))] = null
		var missedKeys: org.apache.spark.rdd.RDD[Int] = null
		var delLRUKeys: org.apache.spark.rdd.RDD[(Int, Int)] = null
		

		var cachedDataCount_new: Long = 0
		var cachedDataCount_old: Long = 0

		var preCogTime: Long = 0
		var preCacheTime: Long = 0
		var preDBTime: Long = 0
		var pre2DBTime: Long = 0
		var preStreamTime: Long = 0
		var pre2StreamTime: Long = 0
		var preCacheRelatedOpTimeDiff: Long = 0

		var currCogTime: Long = 0
		var currCacheTime: Long = 0
		var currDBTime: Long = 0
		var currStreamTime: Long = 0
		var properCachedDataCount: Long = 40000
		var properCachedDataSet: Long = 10

		var isEmpty_missedData = false

		var lastCacheCleaningPosition = 0

		var pass = true
/*
		if(cacheCleaningVersion == "a"){
			var numDelCacheCount = 1
		}else if(cahe)
*/
		var numDelCacheCount = cacheCleaningVersion match {
								case 'a' => -1
								case 'b' => 1
							}
		println("numDCC: " + numDelCacheCount)

		var isContinouousDelCache = false
		var delCacheNum = 0
		//var delCacheNum = -1
		var delCacheNumList: List[Int] = null
		var flush = false


		val ssc = new StreamingContext(sc, Seconds(1))
		//ssc.checkpoint("hdfs://user-241:9000/input")


		val stream = ssc.socketTextStream("192.168.0.243", 9999)
		val stream2 = ssc.socketTextStream("192.168.0.243", 9998)
		val stream3 = ssc.socketTextStream("192.168.0.243", 9997)
		val stream4 = ssc.socketTextStream("192.168.0.243", 9996)
		val stream5 = ssc.socketTextStream("192.168.0.243", 9995)
		val stream6 = ssc.socketTextStream("192.168.0.243", 9994)
		val stream7 = ssc.socketTextStream("192.168.0.243", 9993)
		val stream8 = ssc.socketTextStream("192.168.0.243", 9992)/*
		val stream9 = ssc.socketTextStream("192.168.0.243", 9991)
		val stream10 = ssc.socketTextStream("192.168.0.243", 9990)
		val stream11 = ssc.socketTextStream("192.168.0.243", 9989)
		val stream12 = ssc.socketTextStream("192.168.0.243", 9988)
		val stream13 = ssc.socketTextStream("192.168.0.243", 9987)
		val stream14 = ssc.socketTextStream("192.168.0.243", 9986)
		val stream15 = ssc.socketTextStream("192.168.0.243", 9985)
		val stream16 = ssc.socketTextStream("192.168.0.243", 9984)*/
		
		//var streamAll = sc.union(stream, stream2, stream3, stream4, stream5, stream6, stream7, stream8)
		
		var streamAll = stream.union(stream2).union(stream3).union(stream4)
						.union(stream5).union(stream6).union(stream7).union(stream8)
						//.union(stream9).union(stream10).union(stream11).union(stream12)
						//.union(stream13).union(stream14).union(stream15).union(stream16)

	
		var streaming_data_all: Int = 0
		var time_all = 0		
				
		var hashP = new HashPartitioner(partition_num)
		
		if(isCogroup){
			println("cogroup, partition: " + partition_num)
		}else{
			println("join-subt, partition: " + partition_num)
		}

		var xxx = sc.textFile("file:///home/user/spark/cached40k.tbl", 32)
		cachedPRDD = xxx.map({s=>var k = s.split('|'); var k2 = k(0).split(' '); (k2(0).toInt, k(1).toString)}).partitionBy(hashP)
		cachedPRDD.cache
		cachedDataCount_old = cachedPRDD.count
		cachedDataCount_old

		if(enableCacheCleaningFunction){
			LRUKey = cachedPRDD.map(s => (s._1, 0)).partitionBy(hashP)
			LRUKey.cache.count
			//println("data|LRUKey count: " + t2.cache.count + "\n")
		}		
				
		streamAll.foreachRDD({ rdd =>
			if(!rdd.isEmpty()){

				if(streamingIteration == 60005){
					Thread sleep 100000000
				}

				val tStart = System.currentTimeMillis
				var compSign = 1
				var missCount: Long = 0
				var missKeyCount: Long = 0
				
				println()
				println("Start|Stream num: " + streamingIteration)
				
				var t0 = System.currentTimeMillis
				
				/* Discretize the input stream into RDDs */								
				//var inputPRDD = rdd.map{ s=>  var tmp = s.split("\""); (tmp.lift(3).get.toInt, s) }
				var inputPRDD = rdd.map{ s=>  var tmp = s.split('|'); (tmp.lift(1).get.toInt, s) }

					
				//LRUKeyThread.join()

				if(isCogroup){
					cog = inputPRDD.cogroup(cachedPRDD).filter{s=>(!s._2._1.isEmpty)}
					cog.cache
					cog.count

				}else{
					inputPRDD.cache
					inputPRDD.count

					//LRUKey = LRUKey.union(inputPRDD.map(s => (s._1, streamCount))).partitionBy(hashP)
				}

				var t1 = System.currentTimeMillis

				println("time|1|cogroup (input-cached): " + (t1 - t0) + " ms")
				currCogTime = t1 - t0

/*
				if(cacheCleaning){
					t0 = System.currentTimeMillis
					if(flush){
						LRUKeyTmp = LRUKey.filter(s=> s._2 != delCacheNum)
									.subtractByKey( inputPRDD, hashP )
									.union( cog.map(s => (s._1, streamCount)) ).partitionBy(hashP)
						flush = false
					}else{
						LRUKeyTmp = LRUKey.subtractByKey( inputPRDD, hashP ).union( cog.map(s => (s._1, streamCount)) ).partitionBy(hashP)
					}
					//LRUKeyTmp = LRUKey.subtractByKey( inputPRDD ).union( cog.map(s => (s._1, streamCount)) ).partitionBy(hashP)
					
					//println("data|cog count: " + cog.keys.count)
					//println("data|dist count: " + inputPRDD.keys.distinct.count)
					//LRUKeyTmp = LRUKey.union( inputPRDD.keys.distinct.map(s => (s, streamCount)) ).partitionBy(hashP)
					
					if(streamCount % 100 == 0){
						LRUKeyTmp.checkpoint
					}


					println("data|LRUKey count: " + LRUKeyTmp.cache.count)
					LRUKey.unpersist()
					LRUKey = LRUKeyTmp
					t1 = System.currentTimeMillis
					println("time|2|LRUKey update: " + (t1 - t0) + " ms")
				}
*/				
		

				
				var LRUKeyThread = new Thread(){
					override def run = {
						//var aInputPRDD = inputPRDD
						var a = streamingIteration
						var b = delCacheNum

						if(flush){
							if(cacheCleaningVersion == 'a'){
								LRUKeyTmp = LRUKey.filter(s=> s._2 != b).subtractByKey( inputPRDD, hashP )
												.union( cog.map(s => (s._1, a)) ).partitionBy(hashP)
							}else if(cacheCleaningVersion == 'b'){
								var delList = delCacheNumList
								
								LRUKeyTmp = LRUKey.filter(s => !delList.contains(s._2)).subtractByKey( inputPRDD, hashP )
												.union( cog.map(s => (s._1, a)) ).partitionBy(hashP)
							}
/*
							var delList = delCacheNumList

							LRUKeyTmp = LRUKey.filter(s => !delList.contains(s._2))//LRUKey.filter(s=> s._2 != b)
										.subtractByKey( inputPRDD, hashP )
										.union( cog.map(s => (s._1, a)) ).partitionBy(hashP)
*/
							flush = false
						}else{
							LRUKeyTmp = LRUKey.subtractByKey( inputPRDD, hashP ).union( cog.map(s => (s._1, a)) ).partitionBy(hashP)
						}

						if(streamingIteration % 60 == 0){
							LRUKeyTmp.localCheckpoint
						}

						//LRUKeyTmp = LRUKey.subtractByKey( inputPRDD, hashP ).union( cog.map(s => (s._1, a)) ).partitionBy(hashP)
						//var tmpPRDD = cog.map(s => (s._1, streamCount))
						//t2 = LRUKey.union( tmpPRDD ).partitionBy(hashP)
						//t2 = LRUKey.union( cog.map(s => (s._1, streamCount)) ).partitionBy(hashP)
						LRUKeyTmp.cache.count
						LRUKey.unpersist()
						LRUKey = LRUKeyTmp
					}
				}

				if(enableCacheCleaningFunction){ LRUKeyThread.start }



				/*
				if(streamCount == 1){
					LRUKey = inputPRDD.map(s => (s._1, streamCount)).partitionBy(hashP)
				}else{
					LRUKey = LRUKey.union(inputPRDD.map(s => (s._1, streamCount))).partitionBy(hashP)
				}*/
				
				
									
				/* hit data join */

				val hitThread = new Thread(){
					override def run = {
						var t0 = System.currentTimeMillis
						
						if(isCogroup){
							var r1 = cog.filter{s=> (!s._2._2.isEmpty)}.map{case(x,(y,z)) => ((x,z.head),y.toList)}
							joinedPRDD_hit = r1.flatMapValues(s=>s).map({case((x,y),z)=>(x,(y,z))})
						}else{
							joinedPRDD_hit = inputPRDD.join(cachedPRDD)
						}
												
						joinedPRDD_hit.cache
						//joinedPRDD_hit.count
						println("data|joined Hit count: " + joinedPRDD_hit.count)				
						
						var t1 = System.currentTimeMillis
						println("time|3|join-hit data: " + (t1 - t0) + " ms")
					}
				}
				hitThread.start()				
	
				/* miss data join */
				val f = Future{ // for missed data

					var t0 = System.currentTimeMillis
					var missedRDDThread: Thread = null

					if(isCogroup){			
						missedTmp = cog.filter{x=> x._2._2.isEmpty}
						missedTmp.persist
						missedKeys = missedTmp.keys

						// create missed RDD=,.ginpqtwy]
						missedRDDThread = new Thread(){
							override def run = {
								missedPRDD = missedTmp.map{case(x,(y,z))=>(x,y.toList)}
												.flatMapValues(s=>s).partitionBy(hashP)
								missedPRDD.cache.count
							}
						}
						missedRDDThread.start

					}else{
						missedPRDD = inputPRDD.subtractByKey(cachedPRDD, hashP)
						missedPRDD.cache.count
						missedKeys = missedPRDD.keys.distinct	
					}

					isEmpty_missedData = false
					if(missedKeys.count == 0){
						isEmpty_missedData = true
					}else{
					

					DB_PRDD = missedKeys.mapPartitions(getMissedData)
								.map{ s=> {var tmpAll=s.split(" "); (tmpAll.lift(10).get.toInt, s) } }
								.partitionBy(hashP)
					
					DB_PRDD.persist.count

					t1 = System.currentTimeMillis
					println("time|4|create query + get data + create new RDD: " + (t1 - t0) + " ms")
					currDBTime = t1 - t0


					pass = false
					var cacheRelatedOpTimeDiff = currCogTime - preCogTime + currCacheTime - preCacheTime// cache update
					
					if(preCacheRelatedOpTimeDiff > 0 && cacheRelatedOpTimeDiff < 0 && streamingIteration > 10){
						var c = cacheRelatedOpTimeDiff * (-1)
						if(preCacheRelatedOpTimeDiff > c){
							pass = true
						}
					}
					//var a = currCogTime - preCogTime
					var DBOpTimeDiff = currDBTime - preDBTime
					preCacheRelatedOpTimeDiff = cacheRelatedOpTimeDiff

					//var a = preStreamTime - pre2StreamTime   // c
					//var b = preDBTime - pre2DBTime // c
					
					if(DBOpTimeDiff < 0){ DBOpTimeDiff = DBOpTimeDiff * (-1) }
					var remainNumCachedCount = streamingIteration - delCacheNum

					var preNewCachedDataCount = cachedDataCount_new  // curr cachedDataCount
					var preOldCachedDataCount = cachedDataCount_old

					if(enableCacheCleaningFunction){
						//if(cacheRelatedOpTimeDiff > 0 && cacheRelatedOpTimeDiff > DBOpTimeDiff && streamingIteration > 10 ){
						if(pass || (cacheRelatedOpTimeDiff > 0 && cacheRelatedOpTimeDiff > DBOpTimeDiff && streamingIteration > 10) ){
						//if( b < 0 && a > 0 && streamCount > 10){ // c
							
							if(cacheCleaningVersion == 'a'){
								delCacheNum = delCacheNum + 1
								delLRUKeys = LRUKey.filter(s => s._2 == delCacheNum)
							}else if(cacheCleaningVersion == 'b'){
							
								if(preNewCachedDataCount > properCachedDataCount){
									numDelCacheCount += 1
								}else{
									if(numDelCacheCount != 1){
										numDelCacheCount -= 1
									}									
								}
								if(!isContinouousDelCache){
									properCachedDataCount = (properCachedDataCount + preOldCachedDataCount) / 2
								}

								if(remainNumCachedCount <= numDelCacheCount){
									numDelCacheCount = 1
								}


								println("--- num delete cache count: " + numDelCacheCount)

								delCacheNumList = List.range(delCacheNum, delCacheNum + numDelCacheCount)
								delCacheNum += numDelCacheCount
								delLRUKeys = LRUKey.filter(s => delCacheNumList.contains(s._2))

								lastCacheCleaningPosition = streamingIteration
							}
														
							//for(i <- 1 to numDelCacheCount){}							

							flush = true
							isContinouousDelCache = true
						} else {
/*
							if(isContinouousDelCache == true){
								properCachedDataCount = (properCachedDataCount + preCachedDataCount) / 2
							}
*/


							isContinouousDelCache = false
						}




					}
					
					/* cache management */
					val cacheThread = new Thread(){
						override def run = {
							/*
							if(isCogroup){
								LRUKey = LRUKey.union(cog.map(s => (s._1, streamCount) ))
							}else{
								LRUKey = LRUKey.union(inputPRDD.map(s => (s._1, streamCount)))
							}*/

							//LRUKey.cache.count
							//LRUKey.filter(s=> s._2 == 2).take(10).foreach(println)
							//println(LRUKey.first)

							var t0 = System.currentTimeMillis

						/*	var a = currCogTime + currDBTime - preCogTime - preDBTime
							var b = currCacheTime - preCachedTime
							if(b < 0){ b = b * (-1) }
							
							if( a > 0 && a > b){
								var delLRUKeys = LRUKey.filter(s => s._2 == delCacheNum)
								println(delCacheNum + " | " + delLRUKeys.count)
								//t = cachedPRDD.filter(s => s != delCacheNum).union(DB_PRDD).partitionBy(hashP)
								//t = cachedPRDD.subtractByKey(delLRUKeys).union(DB_PRDD).partitionBy(hashP)
								//t = cachedPRDD.subtractByKey(LRUKey.filter(s => s._2 == delCacheNum)).union(DB_PRDD).partitionBy(hashP)
								//delCacheNum = delCacheNum + 1
								println("perform cache cleaning !!!!!!!!!!!!!!!!!!!!!!!")
							}else{
								t = cachedPRDD.union(DB_PRDD).partitionBy(hashP)
							}
						*/	

							if(enableCacheCleaningFunction){
								if(flush == true){
									println("perform cache cleaning")
																
									cacheTmp = cachedPRDD.subtractByKey(delLRUKeys, hashP).union(DB_PRDD).partitionBy(hashP)
								}else{
									cacheTmp = cachedPRDD.union(DB_PRDD).partitionBy(hashP)	
								}
							}else{
								//t = sc.union(cachedPRDD, DB_PRDD).partitionBy(hashP)	
								
								cacheTmp = cachedPRDD.union(DB_PRDD).partitionBy(hashP)
							}

							if(streamingIteration % 60 == 0){
								//cacheTmp.checkpoint
								cacheTmp.localCheckpoint
							}
							
							
							cachedDataCount_old = cachedDataCount_new

							cachedDataCount_new = cacheTmp.cache.count
							println("data|cachedData: " + cachedDataCount_new)

							cachedPRDD.unpersist()
							cachedPRDD = cacheTmp


							var t1 = System.currentTimeMillis
							println("time|6|create cachedPRDD: " + (t1 - t0) + " ms")
							currCacheTime = t1 - t0
							
							//if(printON)	println(">> cachedPRDD count: " + cachedPRDD.count)
							
							//else cachedPRDD.count
							/* --------------------------------------------------------------------------------- */
							
							/* reorganization
							t0 = System.currentTimeMillis
							if(isFirst){						
								LRUKey = missKey.map(s => (s, streamCount))
								
								//LRUKey.take(10).foreach(println)
							}else{
								//LRUKey.take(10).foreach(println)
								
								LRUKey = LRUKey.subtractByKey(inputPRDD)
								//LRUKey = LRUKey.sortBy(s => s._2)
								
								LRUKey = LRUKey.union(inputPRDD.keys.distinct.map(s => (s,streamCount)))
								LRUKey.repartition(16)
							}
							LRUKey.persist
							
							
							//LRUKey.take(10).foreach(println)
							
							if(printON)	println(">> LRUKey count: " + LRUKey.count)
							else LRUKey.count
							t1 = System.currentTimeMillis
							if(printTime)println("[TIME] create LRUKeyPRDD: " + (t1 - t0) + " ms")
							*/
							
							/*//if(sumCache > 10000){
							if(LRUKey.count > 10000){
								println(">> caching change")
								//var subCount = LRUKey.count.toInt - 10000
								LRUKey = LRUKey.sortBy(s => s._2)
								var rmCount = newPRDD.count.toInt
								var rmData = sc.parallelize(LRUKey.take(rmCount))

							
								cachedPRDD = cachedPRDD.subtractByKey(rmData)
								//LRUKey = sc.parallelize(LRUKey.collect.drop(subCount))
							
							}*/
							/* --------------------------------------------------------------------------------- */
						}
					}
					

					cacheThread.start
					
					/* join missed data */
					t0 = System.currentTimeMillis
					
					if(isCogroup){
						missedRDDThread.join()
					}

					joinedPRDD_missed = missedPRDD.join(DB_PRDD)
					
					joinedPRDD_missed.cache
					var joinedPRDD_missed_count = joinedPRDD_missed.count
					println("data|joined_miss count: " + joinedPRDD_missed_count)
										
					missedPRDD.unpersist()

					t1 = System.currentTimeMillis
					println("time|5|join - miss data: " + (t1 - t0) + " ms")

					cacheThread.join()
					DB_PRDD.unpersist()

					}  	// missed 0 exception

				} // Future


				

				// start Future f (missed data join)
				t0 = System.currentTimeMillis
				val n =Await.result(f, scala.concurrent.duration.Duration.Inf)
				t1 = System.currentTimeMillis
				
				hitThread.join() // hit thread
								
				var outputCount: Long = 0
										
				/* union (joinedRDD_hit, joinedRDD_missed) */
				t0 = System.currentTimeMillis
				if(isEmpty_missedData){
					outputCount = joinedPRDD_hit.count
				}else{
					outputCount = joinedPRDD_hit.union(joinedPRDD_missed).count								
				}				
				println("data|output data: " + outputCount)				
				t1 = System.currentTimeMillis
				println("time|7|union output data: " + (t1 - t0) + " ms")			
				
				streamingIteration = streamingIteration + 1
				//t0 = System.currentTimeMillis
				
				/* unpersist */
				if(isCogroup){
					cog.unpersist()
					missedTmp.unpersist()
				}else{
					inputPRDD.unpersist()
				}
				rdd.unpersist()
				
				isFirst = 0
				//t1 = System.currentTimeMillis
				//println("    [T_8] unpersist(join-hit/miss, input or cogroup): " + (t1 - t0) + " ms")
				

				val tEnd = System.currentTimeMillis
				currStreamTime = tEnd - tStart
				println("time|8|stream: " + currStreamTime + " ms")

				preCogTime = currCogTime
				preCacheTime = currCacheTime
				pre2DBTime = preDBTime
				preDBTime = currDBTime
				pre2StreamTime = preStreamTime
				preStreamTime = currStreamTime

				
				streaming_data_all = streaming_data_all + outputCount.toInt
				println("data|streaming data all: " + streaming_data_all)
				
				var timetmp: Int = tEnd.toInt - tStart.toInt
				time_all = time_all + timetmp
				
				println("time|a|total: " + time_all + " ms")
				
				var throughput = streaming_data_all * 1000 / time_all
				println("END|throughput: " + throughput)

				joinedPRDD_hit.unpersist()
				if(!isEmpty_missedData){
					joinedPRDD_missed.unpersist()
				}

			}
		})
		
		ssc.start()
		ssc.awaitTermination()
	}
	
	def getMissedData(iter: Iterator[(Int)]): Iterator[String] = {
		var count = 0
		var qList: List[com.mongodb.casbah.commons.Imports.DBObject] = null
		var dbData = Array("a")

		var mongoClient = MongoClient("192.168.0.242", 27020)
		val db = mongoClient("n8s10000r")
		var coll = db("part")

		iter.foreach{s => 
			var tmp = MongoDBObject("partkey" -> s); 
			if(count == 0){
				qList = List(tmp)
				count = 1
			}else{
				qList = qList:+tmp
			}
		}
		
		var q = MongoDBObject("$or" -> qList)

		coll.find(q).foreach(s=> dbData = dbData :+ s.toString)
		mongoClient.close 
		
		dbData = dbData.drop(1)						
		dbData.iterator
	}
}




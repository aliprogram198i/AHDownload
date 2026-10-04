package com.ahdownload.app.ui
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import coil3.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahdownload.app.data.DownloadRepository
import com.ahdownload.app.data.DirectUrlResolver
import com.ahdownload.app.data.EmbeddedPlatformResolver
import com.ahdownload.app.data.FormatRanker
import com.ahdownload.app.data.ResolvedFormat
import com.ahdownload.app.diagnostics.AppLogger
import com.ahdownload.app.domain.DownloadJob
import com.ahdownload.app.domain.DownloadStatus
import com.ahdownload.app.ui.theme.AHDownloadTheme
import com.ahdownload.app.ui.theme.AHBrandGradient
import com.ahdownload.app.ui.theme.AHGradientButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private data class LinkAnalysis(val url:String,val title:String,val platform:String,val formats:List<ResolvedFormat>,val durationSeconds:Double? = null,val thumbnailUrl:String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppRoot(){
 AHDownloadTheme{
  val nav=rememberNavController();val entry by nav.currentBackStackEntryAsState();val route=entry?.destination?.route?:"home"
  LaunchedEffect(route){ DesignAudit.recordScreen(route) }
  Scaffold(
   containerColor=MaterialTheme.colorScheme.background,
   topBar={if(route=="downloads"||route=="studio"||route=="settings")TopAppBar(title={Text(if(route=="downloads")"التنزيلات" else if(route=="studio")"Smart Studio" else "الإعدادات",fontWeight=FontWeight.SemiBold)}) else if(route=="accounts"||route=="diagnostics")TopAppBar(title={Text(if(route=="accounts")"الحسابات" else "سجل التطبيق",fontWeight=FontWeight.SemiBold)},navigationIcon={IconButton({nav.popBackStack()}){Icon(Icons.Default.ArrowBack,"رجوع")}})},
   bottomBar={NavigationBar{NavItem(nav,route,"home","الرئيسية",Icons.Default.Home);NavItem(nav,route,"downloads","التنزيلات",Icons.Default.Download);NavItem(nav,route,"studio","الاستوديو",Icons.Default.AutoFixHigh);NavItem(nav,route,"settings","الإعدادات",Icons.Default.Settings)}}
  ){padding->NavHost(nav,"home",Modifier.padding(padding)){composable("home"){HomeScreen{nav.navigate("downloads")}};composable("downloads"){DownloadsScreen()};composable("studio"){StudioScreen()};composable("settings"){SettingsScreen({nav.navigate("diagnostics")},{nav.navigate("accounts")})};composable("accounts"){AccountsScreen()};composable("diagnostics"){DiagnosticsScreen()}}}
 }
}
@Composable private fun NavItem(nav:androidx.navigation.NavHostController,route:String,target:String,label:String,icon:ImageVector){
 Column(
  Modifier
   .fillMaxWidth(0.25f)
   .clickable{
    if(route!=target){
     nav.navigate(target){
      launchSingleTop=true
      restoreState=true
      popUpTo(nav.graph.startDestinationId){saveState=true}
     }
    }
   },
  horizontalAlignment=Alignment.CenterHorizontally,
  verticalArrangement=Arrangement.Center
 ){
  val scale by animateFloatAsState(if(route==target)1.12f else 1f,label="navScale")
  Icon(icon,contentDescription=label,modifier=Modifier.graphicsLayer{scaleX=scale;scaleY=scale},tint=if(route==target)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
  Text(label,style=MaterialTheme.typography.labelSmall,color=if(route==target)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
 }
}

@Composable private fun HomeScreen(openDownloads:()->Unit){
 val context=LocalContext.current;val repository=remember{DownloadRepository.get(context)};val scope=rememberCoroutineScope()
 var url by remember{mutableStateOf("")};var analyzing by remember{mutableStateOf(false)};var analysis by remember{mutableStateOf<LinkAnalysis?>(null)};var error by remember{mutableStateOf<String?>(null)}
 LaunchedEffect(Unit){val intent=(context as? android.app.Activity)?.intent;if(intent?.action==Intent.ACTION_SEND&&intent.type=="text/plain")url=intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()}
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
  item{HomeHeader()}
  item{UrlCard(url,analyzing,{url=it;error=null;analysis=null},{val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;url=clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()}){
   val clean=normalizeInputUrl(url);val platform=detectPlatform(clean)
   val host=runCatching{Uri.parse(clean).host.orEmpty()}.getOrDefault("")
   AppLogger.info(context,"analysis.start","platform="+(platform?:"Direct")+" host="+host+" url_hash="+AppLogger.fingerprint(clean))
   if(host.isBlank()){error="الرابط غير صالح. تحقق من الرابط ثم أعد المحاولة.";return@UrlCard}
   analyzing=true;error=null;analysis=null
   scope.launch{
    if(platform==null){
     DirectUrlResolver().resolve(clean).onSuccess{resolved->
      val formats=resolved.formats.filter{it.hasVideo||it.hasAudio}
      if(formats.isEmpty()){error="الرابط المباشر لم يعرض ملف وسائط قابلًا للتنزيل.";AppLogger.error(context,"analysis.no_formats",details="platform=Direct")}
      else{
       analysis=LinkAnalysis(clean,resolved.title,"ملف مباشر",formats,resolved.durationSeconds,resolved.thumbnail)
       AppLogger.info(context,"analysis.success","platform=Direct formats="+formats.size)
      }
     }.onFailure{failure->error="الرابط لا يشير إلى ملف وسائط قابل للتنزيل.";AppLogger.error(context,"analysis.failed",failure,"platform=Direct")}
    }else{
     EmbeddedPlatformResolver(context).resolve(clean).onSuccess{resolved->
      val formats=resolved.formats.filter{it.hasVideo||it.hasAudio}.let { raw ->
       val video=FormatRanker.rankVideo(raw.filter{it.hasVideo})
       val audio=FormatRanker.rankAudio(raw.filter{it.hasAudio&&!it.hasVideo})
       video + audio
      }
      if(formats.isEmpty()){error="تم الوصول إلى المصدر، لكن لم يتم العثور على صيغ فيديو أو صوت حقيقية.";AppLogger.error(context,"analysis.no_formats",details="platform="+platform)}
      else{analysis=LinkAnalysis(clean,resolved.title,platform,formats,resolved.durationSeconds,resolved.thumbnail);AppLogger.info(context,"analysis.success","platform="+platform+" formats="+formats.size+" video="+formats.count{it.hasVideo}+" audio="+formats.count{it.hasAudio}+" merged="+formats.count{it.mergeRequired})}
     }.onFailure{failure->error="تعذر استخراج وسائط حقيقية من "+platform+". لن يتم حفظ صفحة HTML كفيديو.";AppLogger.error(context,"analysis.failed",failure,"platform="+platform)}
    }
    analyzing=false
   }
  }}
  item{AnimatedVisibility(error!=null){InfoCard(Icons.Default.Warning,"تعذر تحليل الرابط",error.orEmpty())}}
  analysis?.let{info->item{MediaAnalysisCard(info){selected->repository.create(selected.url,buildDownloadTitle(info.title,selected),selected.ext,selected.mergeRequired,selected.audioUrl,selected.audioExt,info.thumbnailUrl,info.durationSeconds?.times(1000L)?.toLong());url="";analysis=null;openDownloads()}}}
 }
}


@Composable private fun HomeHeader(){
 val transition=rememberInfiniteTransition(label="brandPulse")
 val pulse by transition.animateFloat(initialValue=1f,targetValue=1.045f,animationSpec=infiniteRepeatable(tween(1800),RepeatMode.Reverse),label="brandPulseScale")
 Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(28.dp),shadowElevation=2.dp){
  Column(Modifier.background(AHBrandGradient,RoundedCornerShape(28.dp)).padding(22.dp),verticalArrangement=Arrangement.spacedBy(11.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){
    Box(Modifier.size(54.dp).graphicsLayer{scaleX=pulse;scaleY=pulse}.clip(RoundedCornerShape(17.dp)).background(Color.White.copy(alpha=.18f)),contentAlignment=Alignment.Center){
     Icon(Icons.Default.Download,null,tint=Color.White,modifier=Modifier.size(30.dp))
    }
    Spacer(Modifier.width(13.dp))
    Column{
     Text("AHDownload",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=Color.White)
     Text("Smart media downloader",style=MaterialTheme.typography.labelMedium,color=Color.White.copy(alpha=.84f))
    }
   }
   Text("نزّل بذكاء. اختر الأفضل.",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Color.White)
   Text("حلّل الرابط أولاً، ثم اختر الجودة الحقيقية المتاحة من المصدر.",color=Color.White.copy(alpha=.9f))
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    BrandChip(Icons.Default.AutoAwesome,"اختيار ذكي")
    BrandChip(Icons.Default.Verified,"جودة حقيقية")
    BrandChip(Icons.Default.Security,"فحص آمن")
   }
  }
 }
}
@Composable private fun BrandChip(icon:ImageVector,label:String){
 Surface(color=Color.White.copy(alpha=.14f),contentColor=Color.White,shape=RoundedCornerShape(50.dp)){
  Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){
   Icon(icon,null,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text(label,style=MaterialTheme.typography.labelMedium)
  }
 }
}
@Composable private fun UrlCard(url:String,analyzing:Boolean,onUrlChange:(String)->Unit,onPaste:()->Unit,onAnalyze:()->Unit){
 Card(shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text("رابط الفيديو",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
  OutlinedTextField(value=url,onValueChange=onUrlChange,Modifier.fillMaxWidth(),placeholder={Text("الصق رابط الفيديو هنا")},singleLine=true,shape=RoundedCornerShape(16.dp),leadingIcon={Icon(Icons.Default.Link,null)},trailingIcon={if(url.isBlank())IconButton(onPaste){Icon(Icons.Default.ContentPaste,"لصق")}else IconButton({onUrlChange("")}){Icon(Icons.Default.Clear,"مسح")}})
  AHGradientButton(onClick=onAnalyze,enabled=url.trim().startsWith("http")&&!analyzing,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(16.dp)){if(analyzing){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Spacer(Modifier.width(9.dp));Text("جاري استخراج الصيغ…")}else{Icon(Icons.Default.Search,null);Spacer(Modifier.width(8.dp));Text("تحليل الرابط")}}
 }}
}
@Composable private fun InfoCard(icon:ImageVector,title:String,text:String){Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer),shape=RoundedCornerShape(20.dp)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.Top){Icon(icon,null,tint=MaterialTheme.colorScheme.onErrorContainer);Spacer(Modifier.width(12.dp));Column{Text(title,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onErrorContainer);Spacer(Modifier.height(4.dp));Text(text,color=MaterialTheme.colorScheme.onErrorContainer)}}}}

@Composable private fun MediaAnalysisCard(info:LinkAnalysis,onDownload:(ResolvedFormat)->Unit){
 var mode by remember(info.url){mutableStateOf("video")}
 var showMore by remember(info.url){mutableStateOf(false)}

 val videoFormats=remember(info.formats){
  info.formats
   .filter{it.hasVideo&&it.hasAudio}
   .groupBy{it.height?:0}
   .mapNotNull{(_,items)->items.maxWithOrNull(compareBy<ResolvedFormat>{it.sizeBytes?:Long.MAX_VALUE}.thenBy{it.id})}
   .sortedWith(compareByDescending<ResolvedFormat>{it.height?:0}.thenBy{it.sizeBytes?:Long.MAX_VALUE})
 }
 val videoOnlyFormats=remember(info.formats){
  info.formats
   .filter{it.hasVideo&&!it.hasAudio}
   .groupBy{it.height?:0}
   .mapNotNull{(_,items)->items.maxWithOrNull(compareBy<ResolvedFormat>{it.sizeBytes?:Long.MAX_VALUE}.thenBy{it.id})}
   .sortedByDescending{it.height?:0}
 }
 val audioFormats=remember(info.formats){
  info.formats
   .filter{it.hasAudio&&!it.hasVideo}
   .groupBy{it.abr?.toInt()?:0}
   .mapNotNull{(_,items)->items.maxWithOrNull(compareBy<ResolvedFormat>{it.sizeBytes?:Long.MAX_VALUE}.thenBy{it.id})}
   .sortedWith(compareByDescending<ResolvedFormat>{it.abr?:0.0}.thenBy{it.sizeBytes?:Long.MAX_VALUE})
 }
 val list=if(mode=="video")videoFormats else audioFormats
 val recommendedVideo=remember(videoFormats){chooseRecommendedVideo(videoFormats)}
 val recommendedAudio=remember(audioFormats){audioFormats.firstOrNull()}
 var selected by remember(info.url,mode){mutableStateOf(if(mode=="video")recommendedVideo else recommendedAudio)}

 Card(shape=RoundedCornerShape(24.dp)){
  Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer),contentAlignment=Alignment.Center){
     Icon(if(mode=="video")Icons.Default.Movie else Icons.Default.Audiotrack,null,Modifier.size(30.dp))
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)){
     Text(info.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
     Text(info.platform + (info.durationSeconds?.let { " • " + formatDuration(it) } ?: ""),color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
   }
   HorizontalDivider()
   SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
    SegmentedButton(mode=="video",{mode="video";selected=recommendedVideo;showMore=false},shape=SegmentedButtonDefaults.itemShape(0,2)){Text("فيديو")}
    SegmentedButton(mode=="audio",{mode="audio";selected=recommendedAudio;showMore=false},shape=SegmentedButtonDefaults.itemShape(1,2)){Text("صوت")}
   }

   if(list.isEmpty()){
    Text(
     if(mode=="video")"المصدر لم يوفر فيديوً بصوت مدمج ضمن الصيغ الصالحة المعروضة."
     else "المصدر لم يوفر مسار صوت منفصل ضمن الصيغ المستخرجة.",
     color=MaterialTheme.colorScheme.onSurfaceVariant
    )
   } else if(mode=="video"){
    Text("اختيار سريع",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    recommendedVideo?.let{format->
     RecommendedFormatCard(format,selected?.id==format.id,onClick={selected=format})
    }
    videoFormats.filter{it.id!=recommendedVideo?.id}.take(if(showMore) videoFormats.size else 3).forEach{format->
     SimpleFormatRow(format,selected?.id==format.id){selected=format}
    }
    if(videoFormats.size>4){
     TextButton(onClick={showMore=!showMore},modifier=Modifier.fillMaxWidth()){
      Text(if(showMore)"إخفاء الخيارات الإضافية" else "عرض كل الجودات المتاحة ("+videoFormats.size+")")
      Icon(if(showMore)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)
     }
    }
    AHGradientButton({selected?.let(onDownload)},enabled=selected!=null,modifier=Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(16.dp)){
     Icon(Icons.Default.Download,null);Spacer(Modifier.width(8.dp));Text("تنزيل الفيديو")
    }
   } else {
    Text("اختيار سريع",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    recommendedAudio?.let{format->
     RecommendedFormatCard(format,selected?.id==format.id,onClick={selected=format})
    }
    audioFormats.filter{it.id!=recommendedAudio?.id}.take(if(showMore) audioFormats.size else 3).forEach{format->
     SimpleFormatRow(format,selected?.id==format.id){selected=format}
    }
    if(audioFormats.size>4){
     TextButton(onClick={showMore=!showMore},modifier=Modifier.fillMaxWidth()){
      Text(if(showMore)"إخفاء الخيارات الإضافية" else "عرض كل الجودات المتاحة ("+audioFormats.size+")")
      Icon(if(showMore)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)
     }
    }
    AHGradientButton({selected?.let(onDownload)},enabled=selected!=null,modifier=Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(16.dp)){
     Icon(Icons.Default.Download,null);Spacer(Modifier.width(8.dp));Text("تنزيل الصوت")
    }
   }
   Text("الجودات المعروضة مستخرجة فعلياً من المصدر، ويتم تجميع الصيغ المتطابقة لتجنب التكرار.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

@Composable private fun RecommendedFormatCard(format:ResolvedFormat,selected:Boolean,onClick:()->Unit){
 Card(
  onClick=onClick,
  modifier=Modifier.fillMaxWidth(),
  colors=CardDefaults.cardColors(containerColor=animateColorAsState(
   if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
   label="recommendedColor"
  ).value),
  shape=RoundedCornerShape(18.dp)
 ){
  Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){
    Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.primary){
     Text("موصى بها",Modifier.padding(horizontal=9.dp,vertical=5.dp),color=MaterialTheme.colorScheme.onPrimary,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)
    }
    Spacer(Modifier.weight(1f))
    if(selected)Icon(Icons.Default.CheckCircle,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.graphicsLayer{scaleX=1.08f;scaleY=1.08f})
   }
   Text(formatQuality(format),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
   Text(formatDetails(format),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Text("اختيار متوازن للاستخدام اليومي",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

@Composable private fun SimpleFormatRow(format:ResolvedFormat,selected:Boolean,enabled:Boolean=true,onClick:()->Unit){
 OutlinedButton(
  onClick=onClick,
  enabled=enabled,
  modifier=Modifier.fillMaxWidth(),
  shape=RoundedCornerShape(14.dp),
  colors=ButtonDefaults.outlinedButtonColors(
   containerColor=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
  )
 ){
  Icon(if(format.hasVideo)Icons.Default.Movie else Icons.Default.Audiotrack,null)
  Spacer(Modifier.width(8.dp))
  Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start){
   Text(formatQuality(format),fontWeight=FontWeight.SemiBold)
   Text(formatDetails(format),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
  }
  if(selected)Icon(Icons.Default.CheckCircle,null)
 }
}

private fun formatQuality(format:ResolvedFormat):String{
 return format.height?.let{it.toString()+"p"}?:format.abr?.let{it.toInt().toString()+" kbps"}?:"جودة غير محددة"
}

private fun formatDetails(format:ResolvedFormat):String{
 val dimensions=if((format.width?:0)>0&&(format.height?:0)>0)format.width.toString()+"×"+format.height else null
 val audio=when{format.hasVideo&&format.hasAudio->"صوت مدمج";format.hasVideo->"بدون صوت";format.hasAudio->"صوت";else->"وسائط"}
 val size=format.sizeBytes?.let{" • "+formatBytes(it)}?:""
 return listOfNotNull(dimensions,audio,format.ext.takeIf{it.isNotBlank()}?.uppercase(Locale.US),size.removePrefix(" • ").takeIf{it.isNotBlank()}).joinToString(" • ")
}

private fun chooseRecommendedVideo(formats:List<ResolvedFormat>):ResolvedFormat? = FormatRanker.recommendedVideo(formats)

@Composable private fun DownloadsScreen(){
 val context=LocalContext.current;val repository=remember{DownloadRepository.get(context)};val jobs by repository.jobs.collectAsStateWithLifecycle();var filter by remember{mutableStateOf("all")}
 val visible=jobs.filter{when(filter){"active"->it.status in setOf(DownloadStatus.QUEUED,DownloadStatus.DOWNLOADING,DownloadStatus.RETRYING);"completed"->it.status==DownloadStatus.COMPLETED;"failed"->it.status==DownloadStatus.FAILED;else->true}}
 Column(Modifier.fillMaxSize().padding(horizontal=16.dp)){
  Row(Modifier.fillMaxWidth().padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){StatPill(Modifier.weight(1f),"قيد التنفيذ",jobs.count{it.status==DownloadStatus.DOWNLOADING||it.status==DownloadStatus.QUEUED||it.status==DownloadStatus.RETRYING}.toString());StatPill(Modifier.weight(1f),"مكتمل",jobs.count{it.status==DownloadStatus.COMPLETED}.toString())}
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top=12.dp)){listOf("all" to "الكل","active" to "جارية","completed" to "مكتملة","failed" to "فاشلة").forEachIndexed{index,(key,label)->SegmentedButton(filter==key,{filter=key},shape=SegmentedButtonDefaults.itemShape(index,3)){Text(label)}}}
  if(visible.isEmpty())EmptyState(Icons.Default.Download,"لا توجد تنزيلات","ابدأ من الرئيسية بتحليل رابط فيديو.")else LazyColumn(contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){items(visible,key={it.id}){job->DownloadCard(job,{repository.cancel(job.id)},{repository.delete(job.id)},{repository.retry(job.id)},{job.outputUri?.let{uri->openOutput(context,uri)}},{job.outputUri?.let{uri->shareOutput(context,uri)}})}}
 }}
@Composable private fun StatPill(modifier:Modifier,title:String,value:String){Surface(modifier,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.padding(14.dp)){Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(title,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable private fun DownloadCard(job:DownloadJob,onCancel:()->Unit,onDelete:()->Unit,onRetry:()->Unit,onOpen:()->Unit,onShare:()->Unit){Card(shape=RoundedCornerShape(20.dp),modifier=Modifier.animateContentSize()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){
    if(!job.thumbnailUrl.isNullOrBlank()) AsyncImage(model=job.thumbnailUrl,contentDescription="صورة مصغرة",modifier=Modifier.fillMaxSize()) else Icon(if(job.title.contains("kbps",true))Icons.Default.Audiotrack else Icons.Default.Movie,null)
   };Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f)){Text(job.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(statusLabel(job.status) + (job.durationMs?.let { " • " + formatDuration(it / 1000.0) } ?: ""),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(job.status==DownloadStatus.DOWNLOADING || job.status==DownloadStatus.RETRYING) Text((if(job.speedBytesPerSec>0) formatBytes(job.speedBytesPerSec)+"/s" else "جارٍ الحساب") + (job.etaSeconds?.let{" • متبقٍ "+formatDuration(it.toDouble())} ?: ""),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)};Text(job.progress.toString()+"%",fontWeight=FontWeight.Bold)};LinearProgressIndicator(progress={job.progress.coerceIn(0,100)/100f},modifier=Modifier.fillMaxWidth(),trackColor=MaterialTheme.colorScheme.surfaceVariant);Text(job.totalBytes?.let{formatBytes(job.downloadedBytes)+" / "+formatBytes(it)}?:formatBytes(job.downloadedBytes),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){when{job.status==DownloadStatus.DOWNLOADING||job.status==DownloadStatus.QUEUED||job.status==DownloadStatus.RETRYING->TextButton(onClick=onCancel){Text("إلغاء")};job.status==DownloadStatus.COMPLETED&&job.outputUri!=null->{TextButton(onClick=onShare){Text("مشاركة")};TextButton(onClick=onOpen){Text("فتح")};TextButton(onClick=onDelete){Text("حذف")}};job.status==DownloadStatus.FAILED->{TextButton(onClick=onRetry){Text("إعادة المحاولة")};TextButton(onClick=onDelete){Text("حذف")}};else->TextButton(onClick=onDelete){Text("حذف السجل")}}}}}

}

@Composable private fun SettingsScreen(openDiagnostics:()->Unit,openAccounts:()->Unit){
 val context=LocalContext.current;val prefs=remember{context.getSharedPreferences("ahdownload_settings",Context.MODE_PRIVATE)};var wifiOnly by remember{mutableStateOf(prefs.getBoolean("wifi_only",false))};var notifications by remember{mutableStateOf(prefs.getBoolean("notifications",true) && (Build.VERSION.SDK_INT<33 || androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED))}
 val notificationPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
  notifications=granted
  prefs.edit().putBoolean("notifications",granted).apply()
 }
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
  item{Text("إعدادات التنزيل والتشخيص فقط، بدون ميزات جانبية غير مطلوبة.",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)}
  item{SettingsSection("التنزيل"){Setting("التنزيل عبر Wi‑Fi فقط",wifiOnly){wifiOnly=it;prefs.edit().putBoolean("wifi_only",it).apply()};Setting("إشعارات اكتمال التنزيل",notifications){value->
  if(value && Build.VERSION.SDK_INT>=33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
  else {notifications=value;prefs.edit().putBoolean("notifications",value).apply()}
};ListItem(leadingContent={Icon(Icons.Default.Security,null)},headlineContent={Text("فحص الوسائط")},supportingContent={Text("يرفض HTML وصفحات الويب قبل حفظها كفيديو أو صوت.")})}}
  item {
   SettingsSection("الحسابات") {
    ListItem(
     leadingContent={Icon(Icons.Default.AccountCircle,null)},
     headlineContent={Text("ربط الحسابات")},
     supportingContent={Text("YouTube وInstagram وFacebook — جلسة محلية على الجهاز.")},
     trailingContent={TextButton(onClick=openAccounts){Text("فتح")}}
    )
   }
  }
  item {
   SettingsSection("التشخيص") {
    ListItem(
     leadingContent={Icon(Icons.Default.BugReport,null)},
     headlineContent={Text("سجل التطبيق")},
     supportingContent={Text("سجل حقيقي محفوظ محلياً ويمكن نسخه وإرساله للتحليل.")},
     trailingContent={TextButton(onClick=openDiagnostics){Text("فتح")}}
    )
   }
  }
  item {
   SettingsSection("الخصوصية") {
    ListItem(
     leadingContent={Icon(Icons.Default.Lock,null)},
     headlineContent={Text("التحليل محلي")},
     supportingContent={Text("لا يوجد حساب أو اشتراك مفروض لتنزيل الفيديو والصوت الأساسي.")}
    )
   }
  }
 }}
@Composable private fun SettingsSection(title:String,content:@Composable ColumnScope.()->Unit){Card(shape=RoundedCornerShape(20.dp)){Column{Text(title,Modifier.padding(start=16.dp,top=15.dp),fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary);content()}}}
@Composable private fun Setting(title:String,checked:Boolean,onChange:(Boolean)->Unit){ListItem(headlineContent={Text(title)},trailingContent={Switch(checked,onChange)})}
@Composable private fun EmptyState(icon:ImageVector,title:String,subtitle:String){Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){Icon(icon,null,Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary)};Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
private fun openOutput(context:Context,uriString:String){
 val uri=runCatching{Uri.parse(uriString)}.getOrNull()?:return
 val intent=Intent(Intent.ACTION_VIEW).apply{data=uri;addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
 runCatching{context.startActivity(intent)}.onFailure{AppLogger.error(context,"download.open_failed",it,"uri="+AppLogger.fingerprint(uriString))}
}
private fun shareOutput(context:Context,uriString:String){
 val uri=runCatching{Uri.parse(uriString)}.getOrNull()?:return
 val intent=Intent(Intent.ACTION_SEND).apply{type=context.contentResolver.getType(uri)?: "application/octet-stream";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
 runCatching{context.startActivity(Intent.createChooser(intent,"مشاركة الملف"))}.onFailure{AppLogger.error(context,"download.share_failed",it,"uri="+AppLogger.fingerprint(uriString))}
}

private fun formatLabel(format:ResolvedFormat):String{val quality=format.height?.let{it.toString()+"p"}?:format.abr?.let{it.toInt().toString()+" kbps"}?:"جودة غير محددة";val dimensions=if((format.width?:0)>0&&(format.height?:0)>0)format.width.toString()+"×"+format.height else null;val size=format.sizeBytes?.let{" • "+formatBytes(it)}?:"";return quality+(dimensions?.let{" • "+it}?:"")+(if(format.hasAudio)" • صوت" else "")+" • "+format.ext.uppercase(Locale.US)+size}
private fun buildDownloadTitle(title:String,format:ResolvedFormat):String{val quality=format.height?.let{it.toString()+"p"}?:format.abr?.let{it.toInt().toString()+"kbps"}?:format.ext;return title.take(90)+" • "+quality}
private fun statusLabel(status:DownloadStatus)=when(status){DownloadStatus.COMPLETED->"مكتمل";DownloadStatus.DOWNLOADING->"جارٍ التنزيل";DownloadStatus.QUEUED->"في الانتظار";DownloadStatus.RETRYING->"إعادة المحاولة";DownloadStatus.FAILED->"فشل";DownloadStatus.CANCELLED->"ملغى";else->status.name}
private fun normalizeInputUrl(raw:String):String{
 var value=raw.replace(Regex("[\\u0000-\\u001F\\u007F\\u200B-\\u200D\\uFEFF]"),"").trim()
 if(!value.startsWith("http://",true)&&!value.startsWith("https://",true)) value="https://"+value
 return value
}
private fun detectPlatform(url:String):String?{val host=runCatching{Uri.parse(url).host.orEmpty().lowercase(Locale.US).removePrefix("www.")}.getOrDefault("");return when{host=="youtube.com"||host.endsWith(".youtube.com")||host=="youtu.be"->"YouTube";host=="instagram.com"||host.endsWith(".instagram.com")->"Instagram";host=="facebook.com"||host.endsWith(".facebook.com")||host=="fb.watch"->"Facebook";host=="tiktok.com"||host.endsWith(".tiktok.com")->"TikTok";host=="twitter.com"||host.endsWith(".twitter.com")||host=="x.com"||host.endsWith(".x.com")->"X";host=="vimeo.com"||host.endsWith(".vimeo.com")->"Vimeo";host=="reddit.com"||host.endsWith(".reddit.com")->"Reddit";else->null}}
private fun formatDuration(seconds:Double):String{val total=seconds.toLong().coerceAtLeast(0);val h=total/3600;val m=(total%3600)/60;val sec=total%60;return if(h>0)String.format(Locale.US,"%d:%02d:%02d",h,m,sec) else String.format(Locale.US,"%d:%02d",m,sec)}
private fun formatBytes(value:Long):String{if(value<1024)return value.toString()+" B";val units=listOf("KB","MB","GB","TB");var n=value.toDouble();var index=-1;while(n>=1024&&index<units.lastIndex){n/=1024;index++};return String.format(Locale.US,"%.1f %s",n,units[index])}

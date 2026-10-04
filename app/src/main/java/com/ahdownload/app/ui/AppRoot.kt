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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahdownload.app.BuildConfig
import com.ahdownload.app.data.DownloadRepository
import com.ahdownload.app.data.DirectUrlResolver
import com.ahdownload.app.data.EmbeddedPlatformResolver
import com.ahdownload.app.data.FormatRanker
import com.ahdownload.app.data.ResolvedFormat
import com.ahdownload.app.diagnostics.AppLogger
import com.ahdownload.app.domain.DownloadJob
import com.ahdownload.app.domain.DownloadStatus
import com.ahdownload.app.domain.DownloadProfile
import com.ahdownload.app.domain.MediaType
import com.ahdownload.app.ui.theme.AHDownloadTheme
import com.ahdownload.app.ui.theme.AHThemeMode
import com.ahdownload.app.ui.theme.AHBrandGradient
import com.ahdownload.app.ui.theme.AHGradientButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private data class LinkAnalysis(val url:String,val title:String,val platform:String,val formats:List<ResolvedFormat>,val durationSeconds:Double? = null,val thumbnailUrl:String? = null,val mediaType:MediaType = MediaType.UNKNOWN,val profile:DownloadProfile = DownloadProfile.BALANCED)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppRoot(){
 val context=LocalContext.current
 val prefs=remember{context.getSharedPreferences("ahdownload_settings",Context.MODE_PRIVATE)}
 var themeMode by remember{mutableStateOf(runCatching{AHThemeMode.valueOf(prefs.getString("theme_mode",AHThemeMode.SYSTEM.name)?:AHThemeMode.SYSTEM.name)}.getOrDefault(AHThemeMode.SYSTEM))}
 AHDownloadTheme(themeMode=themeMode){
  val nav=rememberNavController();val entry by nav.currentBackStackEntryAsState();val route=entry?.destination?.route?:"home"
  LaunchedEffect(route){ DesignAudit.recordScreen(route) }
  Scaffold(
   containerColor=MaterialTheme.colorScheme.background,
   topBar={when(route){
       "downloads"->AHTopBar("التنزيلات")
       "studio"->AHTopBar("Smart Studio")
       "settings"->AHTopBar("الإعدادات")
       "accounts"->AHTopBar("الحسابات",onBack={nav.popBackStack()})
       "diagnostics"->AHTopBar("سجل التطبيق",onBack={nav.popBackStack()})
      }},
   bottomBar = {
    NavigationBar(
        tonalElevation = 3.dp,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Row(Modifier.fillMaxWidth()) {
            NavItem(nav, route, "home", "الرئيسية", Icons.Default.Home, Modifier.weight(1f))
            NavItem(nav, route, "downloads", "التنزيلات", Icons.Default.Download, Modifier.weight(1f))
            NavItem(nav, route, "studio", "الاستوديو", Icons.Default.AutoFixHigh, Modifier.weight(1f))
            NavItem(nav, route, "settings", "الإعدادات", Icons.Default.Settings, Modifier.weight(1f))
        }
    }
}
  ){padding->NavHost(nav,"home",Modifier.padding(padding)){composable("home"){HomeScreen{nav.navigate("downloads")}};composable("downloads"){DownloadsScreen{nav.navigate("studio")}};composable("studio"){StudioScreen()};composable("settings"){SettingsScreen({nav.navigate("diagnostics")},{nav.navigate("accounts")},themeMode){mode->themeMode=mode;prefs.edit().putString("theme_mode",mode.name).apply()}};composable("accounts"){AccountsScreen()};composable("diagnostics"){DiagnosticsScreen()}}}
 }
}
@Composable
private fun NavItem(
    nav: androidx.navigation.NavHostController,
    route: String,
    target: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val selected = route == target
    val iconContainer by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent,
        label = "navIndicator"
    )
    Column(
        modifier = modifier
            .clickable {
                if (!selected) {
                    DesignAudit.recordInteraction(route, "bottom_nav", "navigate_$target")
                    nav.navigate(target) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                    }
                }
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val scale by animateFloatAsState(
            targetValue = if (selected) 1.08f else 1f,
            label = "navScale"
        )
        Box(
            modifier = Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(14.dp))
                .background(iconContainer)
                .padding(horizontal = 16.dp, vertical = 5.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(23.dp)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable private fun HomeScreen(openDownloads:()->Unit){
 val context=LocalContext.current;val repository=remember{DownloadRepository.get(context)};val scope=rememberCoroutineScope()
 var url by remember{mutableStateOf("")};var analyzing by remember{mutableStateOf(false)};var analysis by remember{mutableStateOf<LinkAnalysis?>(null)};var error by remember{mutableStateOf<String?>(null)}
 val settingsPrefs=remember{context.getSharedPreferences("ahdownload_settings",Context.MODE_PRIVATE)}
 val selectedProfile=DownloadProfile.from(settingsPrefs.getString("download_profile",DownloadProfile.BALANCED.name))
 LaunchedEffect(Unit){val intent=(context as? android.app.Activity)?.intent;if(intent?.action==Intent.ACTION_SEND&&intent.type=="text/plain")url=intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()}
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
  item{HomeHeader()}
  item{LaunchedEffect(Unit){DesignAudit.recordComponent("HomeScreen")}; UrlCard(url,analyzing,{url=it;error=null;analysis=null},{val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;url=clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()}){
   DesignAudit.recordInteraction("home", "url_input", "analyze")
   val clean=normalizeInputUrl(url);val platform=detectPlatform(clean)
   val host=runCatching{Uri.parse(clean).host.orEmpty()}.getOrDefault("")
   AppLogger.info(context,"analysis.start","platform="+(platform?:"Direct")+" host="+host+" url_hash="+AppLogger.fingerprint(clean))
   if(host.isBlank()){error="الرابط غير صالح. تحقق من الرابط ثم أعد المحاولة.";return@UrlCard}
   analyzing=true;error=null;analysis=null
   scope.launch{
    if(platform==null){
     DirectUrlResolver().resolve(clean).onSuccess{resolved->
      val formats=resolved.formats
      if(formats.isEmpty()){error="الرابط المباشر لم يعرض ملفًا قابلاً للتنزيل.";AppLogger.error(context,"analysis.no_formats",details="platform=Direct")}
      else{
       analysis=LinkAnalysis(clean,resolved.title,"ملف مباشر",formats,resolved.durationSeconds,resolved.thumbnail,formats.firstOrNull()?.mediaType ?: MediaType.FILE, selectedProfile)
       AppLogger.info(context,"analysis.success","platform=Direct formats="+formats.size)
      }
     }.onFailure{failure->error="الرابط لا يشير إلى ملف وسائط قابل للتنزيل.";AppLogger.error(context,"analysis.failed",failure,"platform=Direct")}
    }else{
     EmbeddedPlatformResolver(context).resolve(clean).onSuccess{resolved->
      val formats=resolved.formats.filter{it.hasVideo||it.hasAudio}.let { raw ->
       val video=FormatRanker.rankVideo(raw.filter{it.hasVideo},selectedProfile)
       val audio=FormatRanker.rankAudio(raw.filter{it.hasAudio&&!it.hasVideo},selectedProfile)
       video + audio
      }
      if(formats.isEmpty()){error="تم الوصول إلى المصدر، لكن لم يتم العثور على صيغ فيديو أو صوت حقيقية.";AppLogger.error(context,"analysis.no_formats",details="platform="+platform)}
      else{analysis=LinkAnalysis(clean,resolved.title,platform,formats,resolved.durationSeconds,resolved.thumbnail,MediaType.VIDEO,profile.value);AppLogger.info(context,"analysis.success","platform="+platform+" formats="+formats.size+" video="+formats.count{it.hasVideo}+" audio="+formats.count{it.hasAudio}+" merged="+formats.count{it.mergeRequired})}
     }.onFailure{failure->error="تعذر استخراج وسائط حقيقية من "+platform+". لن يتم حفظ صفحة HTML كفيديو.";AppLogger.error(context,"analysis.failed",failure,"platform="+platform)}
    }
    analyzing=false
   }
  }}
  if(analyzing) item{AnalysisSkeleton()}
  item{AnimatedVisibility(error!=null){InfoCard(Icons.Default.Warning,"تعذر تحليل الرابط",error.orEmpty())}}
  analysis?.let{info->item{MediaAnalysisCard(info){selected->repository.create(selected.url,buildDownloadTitle(info.title,selected),selected.ext,selected.mergeRequired,selected.audioUrl,selected.audioExt,selected.httpHeaders,selected.audioHeaders,info.thumbnailUrl,info.durationSeconds?.times(1000L)?.toLong(),info.url);url="";analysis=null;openDownloads()}}}
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
  Text("رابط الوسائط أو الملف",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
  OutlinedTextField(value=url,onValueChange=onUrlChange,Modifier.fillMaxWidth(),placeholder={Text("الصق رابط فيديو أو صوت أو ملف هنا")},singleLine=true,shape=RoundedCornerShape(16.dp),leadingIcon={Icon(Icons.Default.Link,null)},trailingIcon={if(url.isBlank())IconButton(onPaste){Icon(Icons.Default.ContentPaste,"لصق")}else IconButton({onUrlChange("")}){Icon(Icons.Default.Clear,"مسح")}})
  AHGradientButton(onClick=onAnalyze,enabled=url.trim().startsWith("http")&&!analyzing,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(16.dp)){if(analyzing){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Spacer(Modifier.width(9.dp));Text("جاري استخراج الصيغ…")}else{Icon(Icons.Default.Search,null);Spacer(Modifier.width(8.dp));Text("تحليل الرابط")}}
 }}
}
@Composable
private fun AnalysisSkeleton() {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier.fillMaxWidth(.72f)
                            .height(18.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Box(
                        Modifier.fillMaxWidth(.45f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Box(
                Modifier.fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Text(
                "جاري تحليل المصدر واختيار الصيغ الحقيقية…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable private fun InfoCard(icon:ImageVector,title:String,text:String){Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer),shape=RoundedCornerShape(20.dp)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.Top){Icon(icon,null,tint=MaterialTheme.colorScheme.onErrorContainer);Spacer(Modifier.width(12.dp));Column{Text(title,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onErrorContainer);Spacer(Modifier.height(4.dp));Text(text,color=MaterialTheme.colorScheme.onErrorContainer)}}}}

@Composable private fun MediaAnalysisCard(info:LinkAnalysis,onDownload:(ResolvedFormat)->Unit){
 var mode by remember(info.url){mutableStateOf("video")}
 var showMore by remember(info.url){mutableStateOf(false)}
 if(info.mediaType==MediaType.FILE){
  val file=info.formats.firstOrNull()
  Card(shape=RoundedCornerShape(24.dp)){
   Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){
     Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.tertiaryContainer),contentAlignment=Alignment.Center){
      Icon(Icons.Default.InsertDriveFile,null,tint=MaterialTheme.colorScheme.tertiary,modifier=Modifier.size(32.dp))
     }
     Spacer(Modifier.width(12.dp))
     Column(Modifier.weight(1f)){
      Text(info.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
      Text("ملف مباشر",color=MaterialTheme.colorScheme.onSurfaceVariant)
     }
    }
    HorizontalDivider()
    file?.let {
     Text("تفاصيل الملف",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
     Text(file.formatDisplayDetails(),color=MaterialTheme.colorScheme.onSurfaceVariant)
     AHGradientButton({onDownload(file)},modifier=Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(16.dp)){
      Icon(Icons.Default.Download,null);Spacer(Modifier.width(8.dp));Text("تنزيل الملف")
     }
    }
   }
  }
  return
 }

 val videoFormats=remember(info.formats){FormatRanker.rankVideo(info.formats.filter{it.hasVideo}, info.profile)}
 val audioFormats=remember(info.formats){FormatRanker.rankAudio(info.formats.filter{it.hasAudio&&!it.hasVideo}, info.profile)}
 val list=if(mode=="video")videoFormats else audioFormats
 val recommendedVideo=remember(videoFormats){videoFormats.firstOrNull()}
 val recommendedAudio=remember(audioFormats){audioFormats.firstOrNull()}
 var selected by remember(info.url,mode){mutableStateOf(if(mode=="video")recommendedVideo else recommendedAudio)}

 Card(shape=RoundedCornerShape(24.dp)){
  Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer),contentAlignment=Alignment.Center){
     if(!info.thumbnailUrl.isNullOrBlank()) AsyncImage(model=info.thumbnailUrl,contentDescription="صورة مصغرة",modifier=Modifier.fillMaxSize()) else Icon(if(mode=="video")Icons.Default.Movie else Icons.Default.Audiotrack,null,Modifier.size(30.dp))
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
    Text(if(mode=="video")"لا توجد صيغة فيديو متاحة." else "لا توجد صيغة صوت متاحة.",color=MaterialTheme.colorScheme.onSurfaceVariant)
   } else {
    Text("اختيار ذكي",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    selected?.let{format->
     RecommendedFormatCard(format,selected.id==format.id){selected=format}
    }
    list.filter{it.id!=recommendedVideo?.id && it.id!=recommendedAudio?.id}.take(if(showMore) list.size else 4).forEach{format->
     SimpleFormatRow(format,selected?.id==format.id){selected=format}
    }
    if(list.size>5){
     TextButton(onClick={showMore=!showMore},modifier=Modifier.fillMaxWidth()){
      Text(if(showMore)"إخفاء الخيارات الإضافية" else "عرض كل الخيارات ("+list.size+")")
      Icon(if(showMore)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)
     }
    }
    AHGradientButton({selected?.let(onDownload)},enabled=selected!=null,modifier=Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(16.dp)){
     Icon(Icons.Default.Download,null);Spacer(Modifier.width(8.dp));Text(if(mode=="video")"تنزيل الفيديو" else "تنزيل الصوت")
    }
   }
   val multiItems = info.formats.count { it.itemIndex > 0 }
   if (multiItems > 1) {
    TextButton(onClick = { info.formats.filter { it.itemIndex > 0 }.forEach(onDownload) }, modifier = Modifier.fillMaxWidth()) {
     Icon(Icons.Default.DownloadDone, null); Spacer(Modifier.width(5.dp)); Text("\u062a\u0646\u0632\u064a\u0644 \u0643\u0644 \u0627\u0644\u0639\u0646\u0627\u0635\u0631 (" + multiItems + ")")
    }
   }
   Text("الصيغ مبنية على مصادر حقيقية فقط. الصيغة التي تظهر «بدون صوت» يمكن تنزيلها كفيديو فقط، بينما صيغ الدمج المدعومة تعرض «صوت مدمج».",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

@Composable private fun RecommendedFormatCard(format:ResolvedFormat,selected:Boolean,onClick:()->Unit){
 Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=animateColorAsState(if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,label="recommendedColor").value),shape=RoundedCornerShape(18.dp)){
  Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){
    Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.primary){Text(if(format.mergeRequired)"موصى بها • دمج تلقائي" else "موصى بها",Modifier.padding(horizontal=9.dp,vertical=5.dp),color=MaterialTheme.colorScheme.onPrimary,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)}
    Spacer(Modifier.weight(1f))
    if(selected)Icon(Icons.Default.CheckCircle,null,tint=MaterialTheme.colorScheme.primary)
   }
   Text(formatQuality(format),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
   Text(formatDetails(format),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Text(if(format.mergeRequired)"سيتم جمع الفيديو والصوت على الجهاز." else "اختيار متوازن وسهل التشغيل.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

@Composable private fun SimpleFormatRow(format:ResolvedFormat,selected:Boolean,onClick:()->Unit){
 OutlinedButton(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.outlinedButtonColors(containerColor=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)){
  Icon(if(format.hasVideo)Icons.Default.Movie else if(format.hasAudio)Icons.Default.Audiotrack else Icons.Default.InsertDriveFile,null)
  Spacer(Modifier.width(8.dp))
  Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start){
   Text(formatQuality(format),fontWeight=FontWeight.SemiBold)
   Text(formatDetails(format),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
  }
  if(selected)Icon(Icons.Default.CheckCircle,null)
 }
}

private fun formatQuality(format:ResolvedFormat):String{
 return format.itemLabel?.let { it + (format.height?.let { h -> " • " + h + "p" } ?: "") }
  ?: format.height?.let{it.toString()+"p"}
  ?: format.abr?.let{it.toInt().toString()+" kbps"}
  ?: "ملف"
}

private fun ResolvedFormat.formatDisplayDetails():String{
 val size=sizeBytes?.let{formatBytes(it)}?:"الحجم غير معروف"
 return listOfNotNull(ext.uppercase(Locale.US).takeIf{it.isNotBlank()},size).joinToString(" • ")
}

private fun formatDetails(format:ResolvedFormat):String{
 val dimensions=if((format.width?:0)>0&&(format.height?:0)>0)format.width.toString()+"×"+format.height else null
 val codec=format.codec?.substringAfterLast(".")?.takeIf{it.isNotBlank()}
 val fps=format.fps?.let{String.format(Locale.US,"%.0f FPS",it)}
 val bitrate=format.tbr?.takeIf{it>0}?.let{String.format(Locale.US,"%.0f kbps",it)} ?: format.abr?.takeIf{it>0}?.let{String.format(Locale.US,"%.0f kbps",it)}
 val audio=when{format.hasVideo&&format.hasAudio->"صوت مدمج";format.hasVideo->"فيديو فقط";format.hasAudio->"صوت";else->"ملف"}
 val size=format.sizeBytes?.let{formatBytes(it)}
 return listOfNotNull(dimensions,codec,fps,bitrate,audio,format.ext.uppercase(Locale.US).takeIf{it.isNotBlank()},size).joinToString(" • ")
}

@Composable private fun DownloadsScreen(openStudio:()->Unit){
 val context=LocalContext.current
 val repository=remember{DownloadRepository.get(context)}
 val vm:DownloadsViewModel=viewModel()
 val jobs by vm.state.collectAsStateWithLifecycle()
 val allJobs by repository.jobs.collectAsStateWithLifecycle()
 var filter by remember{mutableStateOf(DownloadsViewModel.Filter.ALL)}
 var storage by remember{mutableStateOf(StorageIntelligence.read(context))}
 LaunchedEffect(filter){vm.setFilter(filter)}
 LaunchedEffect(Unit){storage=StorageIntelligence.read(context)}
 Column(Modifier.fillMaxSize().padding(horizontal=16.dp)){
  Row(Modifier.fillMaxWidth().padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
   StatPill(Modifier.weight(1f),"قيد التنفيذ",allJobs.count{it.status==DownloadStatus.DOWNLOADING||it.status==DownloadStatus.QUEUED||it.status==DownloadStatus.RETRYING}.toString())
   StatPill(Modifier.weight(1f),"مكتمل",allJobs.count{it.status==DownloadStatus.COMPLETED}.toString())
   StatPill(Modifier.weight(1f),"المفضلة",allJobs.count{it.favorite}.toString())
  }
  Card(Modifier.fillMaxWidth().padding(top=10.dp),shape=RoundedCornerShape(18.dp)){
   Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){
     Icon(Icons.Default.Storage,null,tint=MaterialTheme.colorScheme.primary)
     Spacer(Modifier.width(8.dp))
     Text("التخزين",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
     Text("${storage.usedPercent}%",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
    }
    LinearProgressIndicator(progress={storage.usedPercent/100f},modifier=Modifier.fillMaxWidth(),trackColor=MaterialTheme.colorScheme.surfaceVariant)
    Text("متاح ${StorageIntelligence.format(storage.availableBytes)} من ${StorageIntelligence.format(storage.totalBytes)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top=12.dp)){
   val filters=listOf(
    DownloadsViewModel.Filter.ALL to "الكل",
    DownloadsViewModel.Filter.ACTIVE to "جارية",
    DownloadsViewModel.Filter.COMPLETED to "مكتملة",
    DownloadsViewModel.Filter.FAILED to "فاشلة",
    DownloadsViewModel.Filter.FAVORITES to "مفضلة"
   )
   filters.forEachIndexed{index,(key,label)->SegmentedButton(filter==key,{filter=key},shape=SegmentedButtonDefaults.itemShape(index,filters.size)){Text(label)}}
  }
  if(jobs.isEmpty()) EmptyState(
   if(filter==DownloadsViewModel.Filter.FAVORITES) Icons.Default.StarBorder else Icons.Default.Download,
   if(filter==DownloadsViewModel.Filter.FAVORITES) "لا توجد مفضلة" else "لا توجد تنزيلات",
   if(filter==DownloadsViewModel.Filter.FAVORITES) "اضغط النجمة على أي تنزيل للاحتفاظ به هنا." else "ابدأ من الرئيسية بتحليل رابط وسائط."
  ) else LazyColumn(contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   items(jobs,key={it.id}){job->
    DownloadCard(
     job,
     {vm.cancel(job.id)},
     {vm.delete(job.id)},
     {vm.retry(job.id)},
     {vm.refreshAndRetry(job.id)},
     {vm.toggleFavorite(job.id)},
     {job.outputUri?.let{uri->openOutput(context,uri)}},
     {job.outputUri?.let{uri->shareOutput(context,uri)}},
     {job.outputUri?.let{uri->StudioBridge.pendingUri=Uri.parse(uri);openStudio()}}
    )
   }
  }
 }
}

@Composable private fun StatPill(modifier:Modifier,title:String,value:String){Surface(modifier,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.padding(12.dp)){Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(title,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

@Composable private fun DownloadCard(
 job:DownloadJob,
 onCancel:()->Unit,
 onDelete:()->Unit,
 onRetry:()->Unit,
 onSmartRetry:()->Unit,
 onToggleFavorite:()->Unit,
 onOpen:()->Unit,
 onShare:()->Unit,
 onStudio:()->Unit
){
 val ext=job.extension.orEmpty().lowercase()
 val icon=when{
  ext in setOf("mp3","m4a","aac","opus","ogg","wav") -> Icons.Default.Audiotrack
  ext in setOf("jpg","jpeg","png","webp") -> Icons.Default.Image
  ext in setOf("pdf","zip","rar","7z","txt","doc","docx") -> Icons.Default.InsertDriveFile
  else -> Icons.Default.Movie
 }
 Card(shape=RoundedCornerShape(20.dp),modifier=Modifier.animateContentSize()){
  Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center){
     if(!job.thumbnailUrl.isNullOrBlank()) AsyncImage(model=job.thumbnailUrl,contentDescription="صورة مصغرة",modifier=Modifier.fillMaxSize())
     else Icon(icon,null,Modifier.size(30.dp),tint=MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.width(11.dp))
    Column(Modifier.weight(1f)){
     Row(verticalAlignment=Alignment.CenterVertically){
      Text(job.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
      IconButton(onClick=onToggleFavorite){
       Icon(if(job.favorite)Icons.Default.Star else Icons.Default.StarBorder,contentDescription=if(job.favorite)"إزالة من المفضلة" else "إضافة للمفضلة",tint=if(job.favorite)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
      }
     }
     Text(statusLabel(job.status)+(job.durationMs?.let{" • "+formatDuration(it/1000.0)}?:""),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     if(job.status==DownloadStatus.DOWNLOADING||job.status==DownloadStatus.RETRYING)
      Text((if(job.speedBytesPerSec>0)formatBytes(job.speedBytesPerSec)+"/s" else "جارٍ الحساب")+(job.etaSeconds?.let{" • متبقٍ "+formatDuration(it.toDouble())}?:""),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
     if(job.status==DownloadStatus.FAILED)
      Text(errorLabel(job.errorCode),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
    }
    Text(job.progress.toString()+"%",fontWeight=FontWeight.Bold)
   }
   LinearProgressIndicator(progress={job.progress.coerceIn(0,100)/100f},modifier=Modifier.fillMaxWidth(),trackColor=MaterialTheme.colorScheme.surfaceVariant)
   Text(job.totalBytes?.let{formatBytes(job.downloadedBytes)+" / "+formatBytes(it)}?:formatBytes(job.downloadedBytes),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
    when{
     job.status==DownloadStatus.DOWNLOADING||job.status==DownloadStatus.QUEUED||job.status==DownloadStatus.RETRYING->TextButton(onClick=onCancel){Text("إلغاء")}
     job.status==DownloadStatus.COMPLETED&&job.outputUri!=null->{
      TextButton(onClick=onStudio){Icon(Icons.Default.AutoFixHigh,null,Modifier.size(18.dp));Spacer(Modifier.width(3.dp));Text("استديو")}
      TextButton(onClick=onShare){Text("مشاركة")}
      TextButton(onClick=onOpen){Text("فتح")}
      TextButton(onClick=onDelete){Text("حذف")}
     }
     job.status==DownloadStatus.FAILED->{
      if(job.errorCode=="MEDIA_SOURCE_REFRESH_FAILED") TextButton(onClick=onSmartRetry){Text("إعادة تحليل المصدر")}
      else TextButton(onClick=onRetry){Text("إعادة المحاولة")}
      TextButton(onClick=onDelete){Text("حذف")}
     }
     else->TextButton(onClick=onDelete){Text("حذف السجل")}
    }
   }
  }
 }
}

private fun errorLabel(code:String?):String=when(code){
 "MEDIA_SOURCE_REFRESH_FAILED"->"انتهت صلاحية المصدر؛ يحتاج تنزيلًا جديدًا من الصفحة الأصلية."
 "HTML_RESPONSE","MEDIA_HTML_OR_ERROR_RESPONSE"->"المصدر أعاد صفحة ويب بدل ملف وسائط."
 "IO_RETRY_EXHAUSTED"->"تعذر الاتصال بعد عدة محاولات."
 "AUTH_SESSION_MISSING"->"يحتاج المصدر إلى جلسة حساب صالحة."
 null->"تعذر إكمال التنزيل."
 else->"تعذر إكمال التنزيل ($code)."
}

@Composable
private fun SettingsScreen(openDiagnostics: () -> Unit, openAccounts: () -> Unit, themeMode: AHThemeMode, onThemeModeChange: (AHThemeMode) -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("ahdownload_settings", Context.MODE_PRIVATE)
    }
    val downloadRepository = remember { DownloadRepository.get(context) }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("wifi_only", false)) }
    var notifications by remember {
        mutableStateOf(
            prefs.getBoolean("notifications", true) &&
                (Build.VERSION.SDK_INT < 33 ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        )
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifications = granted
            prefs.edit().putBoolean("notifications", granted).apply()
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SettingsHeader() }

        item {
            SettingsSection(title = "المظهر", icon = Icons.Default.Palette) {
                ThemeModeSelector(themeMode = themeMode, onChange = onThemeModeChange)
            }
        }

        item {
            SettingsSection(title = "التنزيل", icon = Icons.Default.Download) {
                SettingSwitch(
                    title = "التنزيل عبر Wi‑Fi فقط",
                    supporting = "لا يبدأ التنزيل إلا عند توفر اتصال Wi‑Fi.",
                    checked = wifiOnly,
                    icon = Icons.Default.Wifi,
                    onChange = {
                        wifiOnly = it
                        prefs.edit().putBoolean("wifi_only", it).apply()
                        downloadRepository.applyNetworkPolicy()
                    }
                )
                SettingSwitch(
                    title = "إشعارات التنزيل",
                    supporting = "تنبيه عند اكتمال التنزيل أو فشله.",
                    checked = notifications,
                    icon = Icons.Default.Notifications,
                    onChange = { value ->
                        if (value && Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notifications = value
                            prefs.edit().putBoolean("notifications", value).apply()
                        }
                    }
                )
                ListItem(
                    leadingContent = { SettingsIcon(Icons.Default.Security) },
                    headlineContent = { Text("فحص الوسائط") },
                    supportingContent = { Text("رفض صفحات HTML والتوقيعات غير الصالحة قبل نشر الملف.") }
                )
                ListItem(
                    leadingContent = { SettingsIcon(Icons.Default.AutoAwesome) },
                    headlineContent = { Text("ملف التنزيل الذكي") },
                    supportingContent = {
                        Text(
                            when (profile) {
                                DownloadProfile.BALANCED -> "وازن بين الجودة والتوافق والحجم."
                                DownloadProfile.COMPATIBILITY -> "يفضل MP4/H.264 عندما تتوفر صيغة مناسبة."
                                DownloadProfile.HIGHEST_QUALITY -> "يفضل أعلى دقة ومعدل جودة متاح."
                            }
                        )
                    }
                )
                DownloadProfileSelector(profile) {
                    profile = it
                    prefs.edit().putString("download_profile", it.name).apply()
                }
            }
        }

        item {
            SettingsSection(title = "الحسابات", icon = Icons.Default.AccountCircle) {
                ListItem(
                    leadingContent = { SettingsIcon(Icons.Default.Link) },
                    headlineContent = { Text("الحسابات المرتبطة") },
                    supportingContent = { Text("YouTube وInstagram وFacebook — جلسات محلية على الجهاز.") },
                    trailingContent = { SettingsActionButton("فتح", openAccounts) }
                )
            }
        }

        item {
            SettingsSection(title = "التشخيص", icon = Icons.Default.BugReport) {
                ListItem(
                    leadingContent = { SettingsIcon(Icons.Default.Terminal) },
                    headlineContent = { Text("سجل التطبيق") },
                    supportingContent = { Text("سجل تشغيل حقيقي مع استبعاد البيانات الحساسة.") },
                    trailingContent = { SettingsActionButton("فتح", openDiagnostics) }
                )
            }
        }

        item {
            SettingsSection(title = "الخصوصية", icon = Icons.Default.Lock) {
                ListItem(
                    leadingContent = { SettingsIcon(Icons.Default.Security) },
                    headlineContent = { Text("الخصوصية أولاً") },
                    supportingContent = {
                        Text("سجلات التشخيص لا تتضمن كلمات المرور أو الرموز أو ملفات تعريف الارتباط أو بيانات الاعتماد.")
                    }
                )
            }
        }

        item {
            SettingsSection(title = "حول التطبيق", icon = Icons.Default.Info) {
                ListItem(
                    leadingContent = { SettingsIcon(Icons.Default.Download) },
                    headlineContent = { Text("AHDownload") },
                    supportingContent = {
                        Text("الإصدار " + BuildConfig.VERSION_NAME + " • تنزيل وسائط ذكي")
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsHeader() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .background(AHBrandGradient, RoundedCornerShape(26.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(27.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "إعدادات AHDownload",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "خصص التنزيلات والحسابات والتشخيص من مكان واحد",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.86f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsIcon(icon)
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    supporting: String,
    checked: Boolean,
    icon: ImageVector,
    onChange: (Boolean) -> Unit
) {
    ListItem(
        leadingContent = { SettingsIcon(icon) },
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChange)
        }
    )
}

@Composable
private fun SettingsActionButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ThemeModeSelector(themeMode: AHThemeMode, onChange: (AHThemeMode) -> Unit) {
    val options = listOf(AHThemeMode.SYSTEM to "النظام", AHThemeMode.LIGHT to "فاتح", AHThemeMode.DARK to "داكن")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = themeMode == mode,
                onClick = { onChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = { if (themeMode == mode) Icon(Icons.Default.Check, contentDescription = null) }
            ) { Text(label) }
        }
    }
}

@Composable
private fun DownloadProfileSelector(profile: DownloadProfile, onChange: (DownloadProfile) -> Unit) {
    val options = listOf(
        DownloadProfile.BALANCED to "متوازن",
        DownloadProfile.COMPATIBILITY to "أفضل توافق",
        DownloadProfile.HIGHEST_QUALITY to "أعلى جودة"
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = profile == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = { if (profile == value) Icon(Icons.Default.Check, contentDescription = null) }
            ) { Text(label) }
        }
    }
}

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
private fun buildDownloadTitle(title:String,format:ResolvedFormat):String{val quality=format.height?.let{it.toString()+"p"}?:format.abr?.let{it.toInt().toString()+"kbps"}?:format.ext;val item=format.itemLabel?.let{" • "+it} ?: "";return title.take(82)+item+" • "+quality}
private fun statusLabel(status:DownloadStatus)=when(status){DownloadStatus.COMPLETED->"مكتمل";DownloadStatus.DOWNLOADING->"جارٍ التنزيل";DownloadStatus.QUEUED->"في الانتظار";DownloadStatus.RETRYING->"إعادة المحاولة";DownloadStatus.FAILED->"فشل";DownloadStatus.CANCELLED->"ملغى";else->status.name}
private fun normalizeInputUrl(raw:String):String{
 var value=raw.replace(Regex("[\\u0000-\\u001F\\u007F\\u200B-\\u200D\\uFEFF]"),"").trim()
 if(!value.startsWith("http://",true)&&!value.startsWith("https://",true)) value="https://"+value
 return value
}
private fun detectPlatform(url:String):String?{val host=runCatching{Uri.parse(url).host.orEmpty().lowercase(Locale.US).removePrefix("www.")}.getOrDefault("");return when{host=="youtube.com"||host.endsWith(".youtube.com")||host=="youtu.be"->"YouTube";host=="instagram.com"||host.endsWith(".instagram.com")->"Instagram";host=="facebook.com"||host.endsWith(".facebook.com")||host=="fb.watch"->"Facebook";host=="tiktok.com"||host.endsWith(".tiktok.com")->"TikTok";host=="twitter.com"||host.endsWith(".twitter.com")||host=="x.com"||host.endsWith(".x.com")->"X";host=="vimeo.com"||host.endsWith(".vimeo.com")->"Vimeo";host=="reddit.com"||host.endsWith(".reddit.com")->"Reddit";else->null}}
private fun formatDuration(seconds:Double):String{val total=seconds.toLong().coerceAtLeast(0);val h=total/3600;val m=(total%3600)/60;val sec=total%60;return if(h>0)String.format(Locale.US,"%d:%02d:%02d",h,m,sec) else String.format(Locale.US,"%d:%02d",m,sec)}
private fun formatBytes(value:Long):String{if(value<1024)return value.toString()+" B";val units=listOf("KB","MB","GB","TB");var n=value.toDouble();var index=-1;while(n>=1024&&index<units.lastIndex){n/=1024;index++};return String.format(Locale.US,"%.1f %s",n,units[index])}

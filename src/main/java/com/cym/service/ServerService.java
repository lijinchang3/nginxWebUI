package com.cym.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cym.model.Location;
import com.cym.model.Param;
import com.cym.model.Server;
import com.cym.utils.SnowFlakeUtils;
import com.github.odiszapc.nginxparser.NgxBlock;
import com.github.odiszapc.nginxparser.NgxConfig;
import com.github.odiszapc.nginxparser.NgxEntry;
import com.github.odiszapc.nginxparser.NgxParam;

import cn.craccd.sqlHelper.bean.Page;
import cn.craccd.sqlHelper.bean.Sort;
import cn.craccd.sqlHelper.bean.Sort.Direction;
import cn.craccd.sqlHelper.utils.ConditionAndWrapper;
import cn.craccd.sqlHelper.utils.ConditionOrWrapper;
import cn.craccd.sqlHelper.utils.SqlHelper;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

@Service
public class ServerService {
	private final static Logger log = LoggerFactory.getLogger(ServerService.class);

	@Autowired
	SqlHelper sqlHelper;

	

	public Page search(Page page, String keywords) {
		ConditionAndWrapper conditionAndWrapper = new ConditionAndWrapper();
		if (StrUtil.isNotEmpty(keywords)) {
			conditionAndWrapper.and(new ConditionOrWrapper().like("descr", keywords).like("serverName", keywords.trim()).like("listen", keywords.trim()));
		}

		Sort sort = new Sort().add("seq", Direction.DESC);

		page = sqlHelper.findPage(conditionAndWrapper, sort, page, Server.class);

		return page;
	}

	@Transactional
	public void deleteById(String id) {
		sqlHelper.deleteById(id, Server.class);
		sqlHelper.deleteByQuery(new ConditionAndWrapper().eq("serverId", id), Location.class);
	}

	public List<Location> getLocationByServerId(String serverId) {
		return sqlHelper.findListByQuery(new ConditionAndWrapper().eq("serverId", serverId), Location.class);
	}

	@Transactional
	public void addOver(Server server, String serverParamJson, List<Location> locations) throws Exception {

		if (server.getDef() != null && server.getDef() == 1) {
			clearDef();
		}

		sqlHelper.insertOrUpdate(server);

		List<Param> paramList = new ArrayList<Param>();
		if (StrUtil.isNotEmpty(serverParamJson) && JSONUtil.isJson(serverParamJson)) {
			paramList = JSONUtil.toList(JSONUtil.parseArray(serverParamJson), Param.class);
		}
		List<String> locationIds = sqlHelper.findIdsByQuery(new ConditionAndWrapper().eq("serverId", server.getId()), Location.class);
		sqlHelper.deleteByQuery(new ConditionOrWrapper().eq("serverId", server.getId()).in("locationId", locationIds), Param.class);

		// ????????????,??????????????????????????????
		Collections.reverse(paramList);
		for (Param param : paramList) {
			param.setServerId(server.getId());
			sqlHelper.insert(param);
		}

		sqlHelper.deleteByQuery(new ConditionAndWrapper().eq("serverId", server.getId()), Location.class);

		if (locations != null) {
			// ????????????,??????????????????????????????
			Collections.reverse(locations);

			for (Location location : locations) {
				location.setServerId(server.getId());

				sqlHelper.insert(location);

				paramList = new ArrayList<Param>();
				if (StrUtil.isNotEmpty(location.getLocationParamJson()) && JSONUtil.isJson(location.getLocationParamJson())) {
					paramList = JSONUtil.toList(JSONUtil.parseArray(location.getLocationParamJson()), Param.class);
				}

				// ????????????,??????????????????????????????
				Collections.reverse(paramList);
				for (Param param : paramList) {
					param.setLocationId(location.getId());
					sqlHelper.insert(param);
				}
			}
		}
	}

	private void clearDef() {
		List<Server> servers = sqlHelper.findListByQuery(new ConditionAndWrapper().eq("def", 1), Server.class);
		for (Server server : servers) {
			server.setDef(0);
			sqlHelper.updateById(server);
		}
	}

	@Transactional
	public void addOverTcp(Server server, String serverParamJson) {
		sqlHelper.insertOrUpdate(server);

		List<String> locationIds = sqlHelper.findIdsByQuery(new ConditionAndWrapper().eq("serverId", server.getId()), Location.class);
		sqlHelper.deleteByQuery(new ConditionOrWrapper().eq("serverId", server.getId()).in("locationId", locationIds), Param.class);
		List<Param> paramList = new ArrayList<Param>();
		if (StrUtil.isNotEmpty(serverParamJson) && JSONUtil.isJson(serverParamJson)) {
			paramList = JSONUtil.toList(JSONUtil.parseArray(serverParamJson), Param.class);
		}

		for (Param param : paramList) {
			param.setServerId(server.getId());
			sqlHelper.insert(param);
		}

		sqlHelper.deleteByQuery(new ConditionAndWrapper().eq("serverId", server.getId()), Location.class);
	}

	public List<Server> getListByProxyType(Integer[] proxyType) {
		Sort sort = new Sort().add("seq", Direction.DESC);
		return sqlHelper.findListByQuery(new ConditionAndWrapper().in("proxyType", proxyType), sort, Server.class);
	}



	public void importServer(String nginxPath) throws Exception {
		String initNginxPath = initNginx(nginxPath);
		NgxConfig conf = null;
		try {
			conf = NgxConfig.read(initNginxPath);
		} catch (IOException e) {
			e.printStackTrace();
			throw new Exception("??????????????????");
		}

		List<NgxEntry> servers = conf.findAll(NgxConfig.BLOCK, "server");
		servers.addAll(conf.findAll(NgxConfig.BLOCK, "http", "server"));

		// ????????????,????????????????????????????????????
		Collections.reverse(servers);

		for (NgxEntry ngxEntry : servers) {
			NgxBlock serverNgx = (NgxBlock) ngxEntry;
			NgxParam serverName = serverNgx.findParam("server_name");
			Server server = new Server();
			if (serverName == null) {
				server.setServerName("");
			} else {
				server.setServerName(serverName.getValue());
			}

			server.setProxyType(0);

			// ??????server
			List<NgxEntry> listens = serverNgx.findAll(NgxConfig.PARAM, "listen");
			for (NgxEntry item : listens) {
				NgxParam param = (NgxParam) item;

				if (server.getListen() == null) {
					server.setListen((String) param.getValues().toArray()[0]);
				}

				if (param.getTokens().stream().anyMatch(item2 -> "ssl".equals(item2.getToken()))) {
					server.setSsl(1);
					NgxParam key = serverNgx.findParam("ssl_certificate_key");
					NgxParam perm = serverNgx.findParam("ssl_certificate");
					server.setKey(key == null ? "" : key.getValue());
					server.setPem(perm == null ? "" : perm.getValue());
				}

				if (param.getTokens().stream().anyMatch(item2 -> "http2".equals(item2.getToken()))) {
					server.setHttp2(1);
				}
			}

			long rewriteCount = serverNgx.getEntries().stream().filter(item -> {
				if (item instanceof NgxBlock) {
					NgxBlock itemNgx = (NgxBlock) item;
					if (itemNgx.getEntries().toString().contains("rewrite")) {
						return true;
					}
					return false;
				}
				return false;
			}).count();

			if (rewriteCount > 0) {
				server.setRewrite(1);
			} else {
				server.setRewrite(0);
			}

			// ??????location
			List<Location> locations = new ArrayList<>();
			List<NgxEntry> locationBlocks = serverNgx.findAll(NgxBlock.class, "location");
			for (NgxEntry item : locationBlocks) {
				Location location = new Location();
				// ???????????????http?????? proxy_pass
				NgxParam proxyPassParam = ((NgxBlock) item).findParam("proxy_pass");

				location.setPath(((NgxBlock) item).getValue());
				// ????????????proxy_pass type 0,???????????????????????????????????? type 1
				if (proxyPassParam != null) {
					location.setValue(proxyPassParam.getValue());
					location.setType(0);
				} else {
					NgxParam rootParam = ((NgxBlock) item).findParam("root");
					if (rootParam == null) {
						rootParam = ((NgxBlock) item).findParam("alias");
					}
					if (rootParam == null) {
						continue;
					}

					location.setRootType(rootParam.getName());
					location.setRootPath(rootParam.getValue());

					NgxParam indexParam = ((NgxBlock) item).findParam("index");
					if (indexParam != null) {
						location.setRootPage(indexParam.getValue());
					}

					location.setType(1);
				}
				location.setLocationParamJson(null);
				locations.add(location);
			}

			server.setDef(0);
			server.setSeq( SnowFlakeUtils.getId());
			addOver(server, "", locations);
		}

		// ??????????????????
		FileUtil.del(initNginxPath);
	}

	/**
	 * ????????????????????????????????????????????????????????????#?????????????????????????????????????????????
	 *
	 * @param nginxPath
	 * @return java.lang.String
	 * @author by yanglei 2020/7/5 21:17
	 */
	private String initNginx(String nginxPath) {
		// ?????????????????????java????????????????????????????????????????????????????????????????????????????????????????????????????????????list????????????????????????
		List<String> lines = FileUtil.readLines(nginxPath, CharsetUtil.CHARSET_UTF_8);
		List<String> rs = new ArrayList<>();
		for (String str : lines) {
			if (str.trim().indexOf("#") == 0) {
				continue;
			}
			rs.add(str);
		}

		String initNginxPath = FileUtil.getTmpDirPath() + UUID.randomUUID().toString();
		FileUtil.writeLines(rs, initNginxPath, CharsetUtil.CHARSET_UTF_8);
		return initNginxPath;
	}

	public void setSeq(String serverId, Integer seqAdd) {
		Server server = sqlHelper.findById(serverId, Server.class);

		List<Server> serverList = sqlHelper.findAll(new Sort("seq", Direction.DESC), Server.class);
		if (serverList.size() > 0) {
			Server tagert = null;
			if (seqAdd < 0) {
				// ??????
				for (int i = 0; i < serverList.size(); i++) {
					if (serverList.get(i).getSeq() < server.getSeq()) {
						tagert = serverList.get(i);
						break;
					}
				}
			} else {
				// ??????
				System.out.println(server.getSeq());
				for (int i = serverList.size() - 1; i >= 0; i--) {
					System.out.println(serverList.get(i).getSeq());
					if (serverList.get(i).getSeq() > server.getSeq()) {
						tagert = serverList.get(i);
						break;
					}
				}
			}

			if (tagert != null) {
				
				System.err.println("tagert:" + tagert.getServerName() + tagert.getListen());
				System.err.println("server:" + server.getServerName() + server.getListen());
				
				// ??????seq
				Long seq = tagert.getSeq();
				tagert.setSeq(server.getSeq());
				server.setSeq(seq);

				sqlHelper.updateById(tagert);
				sqlHelper.updateById(server);
			}

		}

	}

}

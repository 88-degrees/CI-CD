package io.onedev.server.mail;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.ibm.icu.impl.locale.XCldrStub.Splitter;
import com.sun.mail.imap.IMAPFolder;
import edu.emory.mathcs.backport.java.util.Arrays;
import io.onedev.commons.bootstrap.Bootstrap;
import io.onedev.commons.loader.ManagedSerializedForm;
import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.StringUtils;
import io.onedev.server.attachment.AttachmentManager;
import io.onedev.server.cluster.ClusterManager;
import io.onedev.server.entitymanager.*;
import io.onedev.server.event.Listen;
import io.onedev.server.event.entity.EntityPersisted;
import io.onedev.server.event.system.SystemStarted;
import io.onedev.server.event.system.SystemStopping;
import io.onedev.server.model.*;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.model.support.administration.IssueCreationSetting;
import io.onedev.server.model.support.administration.SenderAuthorization;
import io.onedev.server.model.support.administration.ServiceDeskSetting;
import io.onedev.server.model.support.administration.emailtemplates.EmailTemplates;
import io.onedev.server.model.support.administration.mailsetting.MailSetting;
import io.onedev.server.model.support.issue.field.supply.FieldSupply;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.persistence.annotation.Sessional;
import io.onedev.server.persistence.annotation.Transactional;
import io.onedev.server.security.permission.AccessProject;
import io.onedev.server.security.permission.ProjectPermission;
import io.onedev.server.security.permission.ReadCode;
import io.onedev.server.util.CollectionUtils;
import io.onedev.server.util.HtmlUtils;
import io.onedev.server.util.ParsedEmailAddress;
import io.onedev.server.validation.validator.UserNameValidator;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.UnauthorizedException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Document.OutputSettings;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unbescape.html.HtmlEscape;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.mail.*;
import javax.mail.internet.*;
import javax.mail.internet.MimeMessage.RecipientType;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.onedev.server.model.Setting.Key.MAIL;
import static java.util.stream.Collectors.toList;

@Singleton
public class DefaultMailManager implements MailManager, Serializable {

	private static final Logger logger = LoggerFactory.getLogger(DefaultMailManager.class);
	
	private static final int MAX_INBOX_LIFE = 3600;
	
	private static final String QUOTE_MARK = "[OneDev]";
	
	private static final String SIGNATURE_PREFIX = "-- ";
	
	private final SettingManager settingManager;
	
	private final TransactionManager transactionManager;
	
	private final ProjectManager projectManager;
	
	private final UserAuthorizationManager authorizationManager;
	
	private final IssueManager issueManager;
	
	private final IssueCommentManager issueCommentManager;
	
	private final IssueWatchManager issueWatchManager;
	
	private final IssueAuthorizationManager issueAuthorizationManager;
	
	private final PullRequestManager pullRequestManager;
	
	private final PullRequestCommentManager pullRequestCommentManager;
	
	private final PullRequestWatchManager pullRequestWatchManager;
	
	private final ExecutorService executorService;
	
	private final UserManager userManager;
	
	private final EmailAddressManager emailAddressManager;
	
	private final UrlManager urlManager;
	
	private final AttachmentManager attachmentManager;
	
	private final ClusterManager clusterManager;
	
	private volatile Thread thread;
	
	@Inject
	public DefaultMailManager(TransactionManager transactionManager, SettingManager settingManager, 
			UserManager userManager, ProjectManager projectManager, 
			UserAuthorizationManager authorizationManager, IssueManager issueManager, 
			IssueCommentManager issueCommentManager, IssueWatchManager issueWatchManager, 
			PullRequestManager pullRequestManager, PullRequestCommentManager pullRequestCommentManager, 
			PullRequestWatchManager pullRequestWatchManager, ExecutorService executorService, 
			UrlManager urlManager, EmailAddressManager emailAddressManager, 
			IssueAuthorizationManager issueAuthorizationManager, AttachmentManager attachmentManager, 
			ClusterManager clusterManager) {
		this.transactionManager = transactionManager;
		this.settingManager = settingManager;
		this.userManager = userManager;
		this.projectManager = projectManager;
		this.authorizationManager = authorizationManager;
		this.issueManager = issueManager;
		this.issueCommentManager = issueCommentManager;
		this.issueWatchManager = issueWatchManager;
		this.pullRequestManager = pullRequestManager;
		this.pullRequestCommentManager = pullRequestCommentManager;
		this.pullRequestWatchManager = pullRequestWatchManager;
		this.executorService = executorService;
		this.urlManager = urlManager;
		this.emailAddressManager = emailAddressManager;
		this.issueAuthorizationManager = issueAuthorizationManager;
		this.attachmentManager = attachmentManager;
		this.clusterManager = clusterManager;
	}

	public Object writeReplace() throws ObjectStreamException {
		return new ManagedSerializedForm(MailManager.class);
	}
	
	@Sessional
	@Override
	public void sendMailAsync(Collection<String> toList, Collection<String> ccList, Collection<String> bccList, 
							  String subject, String htmlBody, String textBody, @Nullable String replyAddress, 
							  @Nullable String senderName, @Nullable String references) {
		transactionManager.runAfterCommit(new Runnable() {

			@Override
			public void run() {
				executorService.execute(new Runnable() {

					@Override
					public void run() {
						try {
							sendMail(toList, ccList, bccList, subject, htmlBody, textBody, replyAddress, 
									senderName, references);
						} catch (Exception e) {
							logger.error("Error sending email (to: " + toList + ", subject: " + subject + ")", e);
						}		
					}
					
				});
			}
			
		});
	}
	
	private String getThreadIndex(String references) {
		byte[] threadIndexBytes = new byte[22];
		FileTime ft = FileTime.fromMillis(System.currentTimeMillis());
		long value = ft.to(TimeUnit.MICROSECONDS);
		ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
		buffer.mark();
		buffer.putLong(value);
		buffer.reset();
		buffer.get(threadIndexBytes, 0, 6);

		byte[] md5Bytes = DigestUtils.md5(references.toString());
		System.arraycopy(md5Bytes, 0, threadIndexBytes, 6, md5Bytes.length);
		return Base64.encodeBase64String(threadIndexBytes);
	}
	
    private String createFoldedHeaderValue(String name, String value) {
    	try {
			return MimeUtility.fold(name.length() + 2, MimeUtility.encodeText(value, StandardCharsets.UTF_8.name(), null));
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
    }

    private InternetAddress createInetAddress(String emailAddress, @Nullable String name) {
        InternetAddress inetAddress;
		try {
			inetAddress = new InternetAddress(emailAddress);
			inetAddress.setPersonal(name);
			inetAddress.validate();
		} catch (AddressException | UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
        return inetAddress;
    }
    
	@Override
	public void sendMail(MailSendSetting sendSetting, Collection<String> toList, Collection<String> ccList, 
						 Collection<String> bccList, String subject, String htmlBody, String textBody, 
						 @Nullable String replyAddress, @Nullable String senderName, 
						 @Nullable String references) {
		if (toList.isEmpty() && ccList.isEmpty() && bccList.isEmpty())
			return;

		if (sendSetting == null) {
			MailSetting mailSetting = settingManager.getMailSetting();
			sendSetting = mailSetting!=null? mailSetting.getSendSetting(): null;
		}
		
		if (sendSetting != null) {
			Properties properties = new Properties();
	        properties.setProperty("mail.smtp.host", sendSetting.getSmtpHost());
			
			sendSetting.getSslSetting().configure(properties);
	 
	        properties.setProperty("mail.smtp.connectiontimeout", String.valueOf(Bootstrap.SOCKET_CONNECT_TIMEOUT));
	        properties.setProperty("mail.smtp.timeout", String.valueOf(sendSetting.getTimeout()*1000));
	        
	        Authenticator authenticator;
	        if (sendSetting.getSmtpUser() != null) {
	        	properties.setProperty("mail.smtp.auth", "true");
	        	if (sendSetting.getSmtpCredential() instanceof OAuthAccessToken)
	        		properties.setProperty("mail.smtp.auth.mechanisms", "XOAUTH2");
	        	String smtpUser = sendSetting.getSmtpUser();
	        	String credentialValue = sendSetting.getSmtpCredential()!=null?sendSetting.getSmtpCredential().getValue():null;
	        	authenticator = new Authenticator() {
		        	
		            @Override
		            protected PasswordAuthentication getPasswordAuthentication() {
		                return new PasswordAuthentication(smtpUser, credentialValue);
		            }
		            
		        };	        	
	        } else {
	        	authenticator = null;
	        }
	        
	        try {
				Session session = Session.getInstance(properties, authenticator);	        
				
				MimeMultipart bodyPart = new MimeMultipart("alternative");
				
				MimeBodyPart htmlPart = new MimeBodyPart();
				htmlPart.setContent(htmlBody, "text/html; charset=" + StandardCharsets.UTF_8.name());
				bodyPart.addBodyPart(htmlPart, 0);
				
				MimeBodyPart textPart = new MimeBodyPart();
				textPart.setText(textBody, StandardCharsets.UTF_8.name());
				bodyPart.addBodyPart(textPart, 0);

				Message message = new MimeMessage(session);
				
				if (references != null) {
				    Map<String, String> headers = CollectionUtils.newHashMap(
				    		"References", references, 
				    		"In-Reply-To", references, 
				    		"Thread-Index", getThreadIndex(references));
				    
				    for (Map.Entry<String, String> entry: headers.entrySet())
				    	message.addHeader(entry.getKey(), createFoldedHeaderValue(entry.getKey(), entry.getValue()));
				}
				
				var brandName = settingManager.getBrandingSetting().getName();
				if (senderName == null || senderName.equalsIgnoreCase(User.SYSTEM_NAME)) {
					if (brandName.equalsIgnoreCase(User.SYSTEM_NAME))
						senderName = QUOTE_MARK;
					else 
						senderName = brandName + " " + QUOTE_MARK;
				} else {
					senderName += " " + QUOTE_MARK;
				}
				message.setFrom(createInetAddress(sendSetting.getSenderAddress(), senderName));
				
				if (toList.isEmpty() && ccList.isEmpty() && bccList.isEmpty())
					throw new ExplicitException("At least one receiver address should be specified");
				
				message.setRecipients(RecipientType.TO, 
						toList.stream().map(it->createInetAddress(it, null)).toArray(InternetAddress[]::new));
				message.setRecipients(RecipientType.CC, 
						ccList.stream().map(it->createInetAddress(it, null)).toArray(InternetAddress[]::new));
				message.setRecipients(RecipientType.BCC, 
						bccList.stream().map(it->createInetAddress(it, null)).toArray(InternetAddress[]::new));
				if (replyAddress != null)
					message.setReplyTo(new InternetAddress[]{createInetAddress(replyAddress, null)});

				message.setSubject(subject);
				message.setContent(bodyPart);

				logger.debug("Sending email (subject: {}, to: {}, cc: {}, bcc: {})... ", subject, toList, ccList, bccList);
				
	            Transport.send(message);
			} catch (MessagingException e) {
				throw new RuntimeException(e);
			}
		} else {
			logger.warn("Unable to send mail as mail setting is not specified");
		}
	}

	@Override
	public void sendMail(Collection<String> toList, Collection<String> ccList, Collection<String> bccList, 
						 String subject, String htmlBody, String textBody, @Nullable String replyAddress, 
						 @Nullable String senderName, @Nullable String references) {
		sendMail(null, toList, ccList, bccList, subject, htmlBody, textBody, replyAddress, 
				senderName, references);
	}
	
	@Transactional
	@Listen
	public void on(EntityPersisted event) {
		if (event.getEntity() instanceof Setting) {
			Setting setting = (Setting) event.getEntity();
			if (setting.getKey() == MAIL) {
				transactionManager.runAfterCommit(() -> clusterManager.submitToServer(clusterManager.getLeaderServerAddress(), () -> {
					Thread copy = thread;
					if (copy != null)
						copy.interrupt();
					return null;
				}));
			}
		}
	}

	private void checkPermission(InternetAddress sender, Project project, Permission privilege, 
			@Nullable User user, @Nullable SenderAuthorization authorization) {
		if ((user == null || !user.asSubject().isPermitted(new ProjectPermission(project, privilege))) 
				&& (authorization == null || !authorization.isPermitted(project, privilege))) {
			String errorMessage = String.format("Permission denied (project: %s, sender: %s, permission: %s)", 
					project.getPath(), sender.getAddress(), privilege.getClass().getName());
			throw new UnauthorizedException(errorMessage);
		}
	}
	
	@SuppressWarnings("unchecked")
	@Transactional
	protected void onMessage(MailSendSetting sendSetting, MailCheckSetting checkSetting, Message message) throws MessagingException, IOException {
		String[] toHeader = message.getHeader("To");
		String[] fromHeader = message.getHeader("From");
		String[] ccHeader = message.getHeader("Cc");
		if (toHeader != null && toHeader.length != 0) {
			if (fromHeader == null || fromHeader.length == 0)
				throw new ExplicitException("Invalid email message: no from address found");
			
			if (!fromHeader[0].equalsIgnoreCase(checkSetting.getCheckAddress())) {
				InternetAddress from = InternetAddress.parse(fromHeader[0], true)[0];

				EmailAddress fromAddressEntity = emailAddressManager.findByValue(from.getAddress());
				if (fromAddressEntity != null && !fromAddressEntity.isVerified()) {
					logger.error("Another account uses email address '{}' but not verified", from.getAddress());
				} else {
					User user = fromAddressEntity != null ? fromAddressEntity.getOwner() : null;
					SenderAuthorization authorization = null;
					String designatedProject = null;
					ServiceDeskSetting serviceDeskSetting = settingManager.getServiceDeskSetting();
					if (serviceDeskSetting != null) {
						authorization = serviceDeskSetting.getSenderAuthorization(from.getAddress());
						designatedProject = serviceDeskSetting.getDesignatedProject(from.getAddress());
					}
					ParsedEmailAddress parsedSystemAddress = ParsedEmailAddress.parse(checkSetting.getCheckAddress());
					logger.trace("Parsed system address: " + parsedSystemAddress);

					Collection<Issue> issues = new ArrayList<>();
					Collection<PullRequest> pullRequests = new ArrayList<>();
					Collection<InternetAddress> involved = new ArrayList<>();

					List<InternetAddress> receivers = new ArrayList<>();
					receivers.addAll(Arrays.asList(InternetAddress.parse(toHeader[0], true)));

					if (ccHeader != null && ccHeader.length != 0)
						receivers.addAll(Arrays.asList(InternetAddress.parse(ccHeader[0], true)));

					List<String> receiverEmailAddresses =
							receivers.stream().map(InternetAddress::getAddress).collect(toList());

					for (InternetAddress receiver : receivers) {
						logger.trace("Processing on behalf of receiver '" + receiver.getAddress() + "'");

						ParsedEmailAddress parsedReceiverAddress = ParsedEmailAddress.parse(receiver.getAddress());

						logger.trace("Parsed receiver address: " + parsedReceiverAddress);

						if (parsedReceiverAddress.toString().equals(parsedSystemAddress.toString())) {
							logger.trace("Message is targeting system address");
							if (serviceDeskSetting != null) {
								if (designatedProject == null)
									throw new ExplicitException("No project designated for sender: " + from.getAddress());
								Project project = projectManager.findByPath(designatedProject);
								if (project == null) {
									String errorMessage = String.format(
											"Sender project does not exist (sender: %s, project: %s)",
											from.getAddress(), designatedProject);
									throw new ExplicitException(errorMessage);
								}
								checkPermission(from, project, new AccessProject(), user, authorization);
								issues.add(openIssue(message, project, from, user, authorization, parsedSystemAddress));
							} else {
								throw new ExplicitException("Unable to create issue from email as service desk is not enabled");
							}
						} else if (parsedReceiverAddress.getDomain().equals(parsedSystemAddress.getDomain())
								&& parsedReceiverAddress.getName().startsWith(parsedSystemAddress.getName() + "+")) {
							String subAddress = parsedReceiverAddress.getName().substring(parsedSystemAddress.getName().length() + 1);
							if (subAddress.equals(MailManager.TEST_SUB_ADDRESS)) {
								continue;
							} else if (subAddress.contains("~")) {
								logger.trace("Message is targeting a sub address");

								Long entityId;
								try {
									entityId = Long.parseLong(StringUtils.substringAfter(subAddress, "~"));
								} catch (NumberFormatException e) {
									throw new ExplicitException("Invalid id specified in receipient address: " + parsedReceiverAddress);
								}
								if (subAddress.contains("issue")) {
									logger.trace("Message is an issue reply");

									Issue issue = issueManager.get(entityId);
									if (issue == null)
										throw new ExplicitException("Non-existent issue specified in receipient address: " + parsedReceiverAddress);
									if (subAddress.contains("unsubscribe")) {
										if (user != null) {
											IssueWatch watch = issueWatchManager.find(issue, user);
											if (watch != null) {
												watch.setWatching(false);
												issueWatchManager.update(watch);
												String subject = "Unsubscribed successfully from issue " + issue.getFQN();
												String template = settingManager.getEmailTemplates().getIssueNotificationUnsubscribed();

												Map<String, Object> bindings = new HashMap<>();
												bindings.put("issue", issue);

												String htmlBody = EmailTemplates.evalTemplate(true, template, bindings);
												String textBody = EmailTemplates.evalTemplate(false, template, bindings);

												sendMailAsync(Lists.newArrayList(from.getAddress()), Lists.newArrayList(), Lists.newArrayList(),
														subject, htmlBody, textBody, null, null, getMessageId(message));
											}
										}
									} else {
										checkPermission(from, issue.getProject(), new AccessProject(), user, authorization);
										addComment(sendSetting, issue, message, from, receiverEmailAddresses, user, authorization);
										issues.add(issue);
									}
								} else if (subAddress.contains("pullrequest")) {
									logger.trace("Message is a pull request reply");

									PullRequest pullRequest = pullRequestManager.get(entityId);
									if (pullRequest == null)
										throw new ExplicitException("Non-existent pull request specified in receipient address: " + parsedReceiverAddress);
									if (subAddress.contains("unsubscribe")) {
										if (user != null) {
											PullRequestWatch watch = pullRequestWatchManager.find(pullRequest, user);
											if (watch != null) {
												watch.setWatching(false);
												pullRequestWatchManager.update(watch);
												String subject = "Unsubscribed successfully from pull request " + pullRequest.getFQN();

												String template = StringUtils.join(settingManager.getEmailTemplates().getPullRequestNotificationUnsubscribed(), "\n");
												Map<String, Object> bindings = new HashMap<>();
												bindings.put("pullRequest", pullRequest);
												String htmlBody = EmailTemplates.evalTemplate(true, template, bindings);
												String textBody = EmailTemplates.evalTemplate(false, template, bindings);
												sendMailAsync(Lists.newArrayList(from.getAddress()), Lists.newArrayList(), Lists.newArrayList(),
														subject, htmlBody, textBody, null, null, getMessageId(message));
											}
										}
									} else {
										checkPermission(from, pullRequest.getTargetProject(), new ReadCode(), user, authorization);
										addComment(sendSetting, pullRequest, message, from, receiverEmailAddresses, user, authorization);
										pullRequests.add(pullRequest);
									}
								} else {
									throw new ExplicitException("Invalid receipient address: " + parsedReceiverAddress);
								}
							} else {
								logger.trace("Message is targeting service desk '" + subAddress + "'...");

								Project project = projectManager.findByServiceDeskName(subAddress);
								if (project == null)
									project = projectManager.findByPath(subAddress);

								if (project == null)
									throw new ExplicitException("Non-existent project specified in receipient address: " + parsedReceiverAddress);
								if (serviceDeskSetting != null) {
									checkPermission(from, project, new AccessProject(), user, authorization);
									logger.debug("Creating issue via email (project: {})...", project.getPath());
									issues.add(openIssue(message, project, from, user, authorization, parsedSystemAddress));
								} else {
									throw new ExplicitException("Unable to create issue from email as service desk is not enabled");
								}
							}
						} else {
							logger.trace("Adding receiver to involved list");
							involved.add(receiver);
						}
					}

					for (Issue issue : issues) {
						for (InternetAddress each : involved) {
							EmailAddress emailAddressEntity = emailAddressManager.findByValue(each.getAddress());
							if (emailAddressEntity != null && !emailAddressEntity.isVerified()) {
								logger.error("Another account uses email address '{}' but not verified", each.getAddress());
							} else {
								if (serviceDeskSetting != null)
									authorization = serviceDeskSetting.getSenderAuthorization(each.getAddress());
								user = emailAddressEntity != null ? emailAddressEntity.getOwner() : null;
								try {
									checkPermission(each, issue.getProject(), new AccessProject(), user, authorization);
									if (user == null)
										user = createUser(each, issue.getProject(), authorization.getAuthorizedRole());
									issueWatchManager.watch(issue, user, true);
									if (issue.isConfidential())
										issueAuthorizationManager.authorize(issue, user);
								} catch (UnauthorizedException e) {
									logger.error("Error adding receipient to watch list", e);
								}
							}
						}
					}
					for (PullRequest pullRequest : pullRequests) {
						for (InternetAddress each : involved) {
							EmailAddress emailAddressEntity = emailAddressManager.findByValue(each.getAddress());
							if (emailAddressEntity != null && !emailAddressEntity.isVerified()) {
								logger.error("Another account uses email address '{}' but not verified", each.getAddress());
							} else {
								user = emailAddressEntity != null ? emailAddressEntity.getOwner() : null;
								if (serviceDeskSetting != null)
									authorization = serviceDeskSetting.getSenderAuthorization(each.getAddress());
								try {
									checkPermission(each, pullRequest.getProject(), new ReadCode(), user, authorization);
									if (user == null)
										user = createUser(each, pullRequest.getProject(), authorization.getAuthorizedRole());
									pullRequestWatchManager.watch(pullRequest, user, true);
								} catch (UnauthorizedException e) {
									logger.error("Error adding receipient to watch list", e);
								}
							}
						}
					}
				}
			} else {
				logger.warn("Ignore message as 'From' is same as system email address");
			}
		} else {
			logger.warn("Ignore message as 'To' header is not available");
		}
	}
	
	private void removeNodesAfter(Node node) {
		Node current = node;
		while (current != null) {
			Node nextSibling = current.nextSibling();
			while (nextSibling != null) {
				Node temp = nextSibling.nextSibling();
				nextSibling.remove();
				nextSibling = temp;
			}
			current = current.parent();
		}
	}
	
	@Nullable
	private String stripQuotationAndSignature(MailSendSetting sendSetting, String content) {
		String quotationMark = null;
		if (content.contains(QUOTE_MARK)) {
			quotationMark = QUOTE_MARK;
		} else if (content.contains(sendSetting.getSenderAddress())) {
			quotationMark = sendSetting.getSenderAddress();
		} else if (sendSetting.getSmtpUser() != null 
				&& sendSetting.getSmtpUser().contains("@") 
				&& !sendSetting.getSmtpUser().equalsIgnoreCase(sendSetting.getSenderAddress())) {
			quotationMark = sendSetting.getSmtpUser();
		}
		
		if (quotationMark != null) {
			content = StringUtils.substringBefore(content, quotationMark);
			content = StringUtils.substringBeforeLast(content, "\n");
		}
		
		Document document = HtmlUtils.parse(stripTextSignature(content));
		document.select(".gmail_quote").remove();
		document.outputSettings().prettyPrint(false);
		
		return getContent(document);
	}

	private String stripTextSignature(String content) {
		var lines = new ArrayList<>();
		for (var line: Splitter.on('\n').split(content)) {
			if (line.contains(SIGNATURE_PREFIX)) {
				Document document = HtmlUtils.parse(line);				
				if (document.wholeText().trim().equals(SIGNATURE_PREFIX.trim()))
					break;
				else 
					lines.add(line);
			} else {
				lines.add(line);
			}
		}
		return StringUtils.join(lines, "\n");
	}
	
	@Nullable
	private String stripSignature(String content) {
		Document document = HtmlUtils.parse(stripTextSignature(content));
		document.outputSettings().prettyPrint(false);
		return getContent(document);
	}	
	
	@Nullable
	private String getContent(Document document) {
		AtomicReference<Node> lastContentNodeRef = new AtomicReference<>(null);
		
		NodeTraversor.traverse(new NodeVisitor() {
			
			@Override
			public void tail(Node node, int depth) {
				if (node instanceof Element && ((Element) node).tagName().equals("img")) {
					lastContentNodeRef.set(node);
				} else if (node instanceof TextNode) {
					String text = ((TextNode) node).getWholeText();
					char nbsp = 160;
					if (StringUtils.isNotBlank(StringUtils.replaceChars(text, nbsp, ' '))) 
						lastContentNodeRef.set(node);
				}
			}
			
			@Override
			public void head(Node node, int depth) {
				
			}
			
		}, document);

		Node lastContentNode = lastContentNodeRef.get();
		if (lastContentNode != null) {
			removeNodesAfter(lastContentNode);
			return document.body().html();
		} else {
			return null;
		}
	}
	
	private String decorateContent(String content) {
		// Add double line breaks in the beginning and ending as otherwise plain text content 
		// with multiple paragraphs received from email may not be formatted correctly with 
		// our markdown renderer. 
		return String.format("<div class='%s'>\n\n", COMMENT_MARKER) + content + "\n\n</div>";
	}
	
	private void addComment(MailSendSetting sendSetting, Issue issue, Message message, InternetAddress author, 
			Collection<String> receiverEmailAddresses, @Nullable User user, 
			@Nullable SenderAuthorization authorization) throws IOException, MessagingException {
		IssueComment comment = new IssueComment();
		comment.setIssue(issue);
		if (user == null)
			user = createUser(author, issue.getProject(), authorization.getAuthorizedRole());
		logger.trace("Creating issue comment on behalf of user '" + user.getName() + "'");
		comment.setUser(user);
		String content = stripQuotationAndSignature(sendSetting, getText(issue.getProject(), issue.getUUID(), message));
		if (content != null) {
			// Add double line breaks in the beginning and ending as otherwise plain text content 
			// received from email may not be formatted correctly with our markdown renderer. 
			comment.setContent(decorateContent(content));
			issueCommentManager.create(comment, receiverEmailAddresses);
		}
	}
	
	private void addComment(MailSendSetting sendSetting, PullRequest pullRequest, Message message, InternetAddress author, 
			Collection<String> receiverEmailAddresses, @Nullable User user, 
			@Nullable SenderAuthorization authorization) throws IOException, MessagingException {
		PullRequestComment comment = new PullRequestComment();
		comment.setRequest(pullRequest);
		if (user == null)
			user = createUser(author, pullRequest.getProject(), authorization.getAuthorizedRole());
		logger.trace("Creating pull request comment on behalf of user '" + user.getName() + "'");
		comment.setUser(user);
		String content = stripQuotationAndSignature(sendSetting, getText(pullRequest.getProject(), pullRequest.getUUID(), message, null));
		if (content != null) {
			comment.setContent(decorateContent(content));
			pullRequestCommentManager.create(comment, receiverEmailAddresses);
		}
	}
	
	@Nullable
	private String getMessageId(Message message) throws MessagingException {
		String[] messageId = message.getHeader("Message-ID");
		if (messageId != null && messageId.length != 0)
			return messageId[0];
		else
			return null;
	}
	
	private Issue openIssue(Message message, Project project, InternetAddress submitter, 
			@Nullable User user, @Nullable SenderAuthorization authorization, 
			ParsedEmailAddress parsedSystemAddress) throws MessagingException, IOException {
		Issue issue = new Issue();
		issue.setProject(project);
		if (StringUtils.isNotBlank(message.getSubject())) {
			if (message.getSubject().trim().toLowerCase().startsWith("re:")) {
				throw new ExplicitException("This address is intended to open issues, " +
						"however the message looks like a reply to some other email");
			}
			issue.setTitle(message.getSubject());
		} else {
			throw new ExplicitException("Subject required to open issue via email");
		}
		
		String messageId = getMessageId(message);
		if (messageId != null)
			issue.setThreadingReference(messageId);

		String description = getText(project, issue.getUUID(), message);
		if (StringUtils.isNotBlank(description)) 
			description = stripSignature(description);
		if (StringUtils.isNotBlank(description))
			issue.setDescription(decorateContent(description));

		if (user == null)
			user = createUser(submitter, project, authorization.getAuthorizedRole());
		issue.setSubmitter(user);
		
		GlobalIssueSetting issueSetting = settingManager.getIssueSetting();
		issue.setState(issueSetting.getInitialStateSpec().getName());
		
		IssueCreationSetting issueCreationSetting = settingManager.getServiceDeskSetting()
				.getIssueCreationSetting(submitter.getAddress(), project);
		issue.setConfidential(issueCreationSetting.isConfidential());
		for (FieldSupply supply: issueCreationSetting.getIssueFields()) {
			Object fieldValue = issueSetting.getFieldSpec(supply.getName())
					.convertToObject(supply.getValueProvider().getValue());
			issue.setFieldValue(supply.getName(), fieldValue);
		}
		
		issueManager.open(issue);
		
		ParsedEmailAddress parsedSubmitterAddress = ParsedEmailAddress.parse(submitter.getAddress());
		if (!parsedSubmitterAddress.getDomain().equalsIgnoreCase(parsedSystemAddress.getDomain()) 
				|| !parsedSubmitterAddress.getName().toLowerCase().startsWith(parsedSystemAddress.getName().toLowerCase() + "+") 
						&& !parsedSubmitterAddress.getName().equalsIgnoreCase(parsedSystemAddress.getName())) {
			
			String template = StringUtils.join(settingManager.getEmailTemplates().getServiceDeskIssueOpened(), "\n");
			Map<String, Object> bindings = new HashMap<>();
			bindings.put("issue", issue);
			String htmlBody = EmailTemplates.evalTemplate(true, template, bindings);
			String textBody = EmailTemplates.evalTemplate(false, template, bindings);
			
			sendMailAsync(Lists.newArrayList(submitter.getAddress()), Lists.newArrayList(), Lists.newArrayList(),
					"Re: " + issue.getTitle(), htmlBody, textBody, getReplyAddress(issue), 
					submitter.getPersonal(), issue.getEffectiveThreadingReference()); 
		}
		return issue;
	}

	private User createUser(InternetAddress address, Project project, Role role) {
		User user = new User();
		user.setName(UserNameValidator.suggestUserName(ParsedEmailAddress.parse(address.getAddress()).getName()));
		user.setFullName(address.getPersonal());
		user.setPassword("impossible password");
		userManager.create(user);
		
		EmailAddress emailAddress = new EmailAddress();
		emailAddress.setValue(address.getAddress());
		emailAddress.setVerificationCode(null);
		emailAddress.setPrimary(true);
		emailAddress.setGit(true);
		emailAddress.setOwner(user);
		emailAddressManager.create(emailAddress);
		
		boolean found = false;
		for (UserAuthorization authorization: user.getProjectAuthorizations()) {
			if (authorization.getProject().equals(project)) {
				found = true;
				break;
			}
		}
		if (!found) {
			UserAuthorization authorization = new UserAuthorization();
			authorization.setUser(user);
			authorization.setProject(project);
			authorization.setRole(role);
			authorizationManager.create(authorization);
		}
		
		return user;
	}
	
	@Listen
	public void on(SystemStarted event) {
		clusterManager.getHazelcastInstance().getCluster().addMembershipListener(new MembershipListener() {

			@Override
			public void memberAdded(MembershipEvent membershipEvent) {
			}

			@Override
			public void memberRemoved(MembershipEvent membershipEvent) {
				if (clusterManager.isLeaderServer()) {
					Thread copy = thread;
					if (copy != null)
						copy.interrupt();
				}
			}
			
		});
		thread = new Thread(() -> {
			while (thread != null) {
				try {
					MailSetting mailSetting = settingManager.getMailSetting();
					MailCheckSetting checkSetting = mailSetting!=null?mailSetting.getCheckSetting():null;
					if (checkSetting != null && clusterManager.isLeaderServer()) {
						MailSendSetting sendSetting = mailSetting.getSendSetting();
						MailPosition mailPosition = new MailPosition();
						while (thread != null) {
							Future<?> future = monitorInbox(checkSetting, message -> 
									onMessage(sendSetting, checkSetting, message), mailPosition, false);
							
							try {
								future.get();
							} catch (InterruptedException e) {
								future.cancel(true);
								throw e;
							} catch (ExecutionException e) {
								if (ExceptionUtils.find(e, FolderClosedException.class) == null)
									logger.error("Error monitoring inbox", e);
								else
									logger.warn("Lost connection to mail server, will reconnect later... ");
								Thread.sleep(5000);
							}
						}
					} else {
						Thread.sleep(60000);
					}
				} catch (InterruptedException ignored) {
				}
			}
		});
		thread.start();
	}
	
	@Listen
	public void on(SystemStopping event) {
		Thread copy = thread;
		thread = null;
		if (copy != null) {
			copy.interrupt();
			try {
				copy.join();
			} catch (InterruptedException ignored) {
			}
		}
	}
	
	@Override
	public Future<?> monitorInbox(MailCheckSetting checkSetting, MessageListener listener, 
								  MailPosition lastPosition, boolean testMode) {
		return executorService.submit(new Runnable() {

			private void processMessages(IMAPFolder inbox, AtomicInteger messageNumber) throws MessagingException {
				int messageCount = inbox.getMessageCount();
				for (int i=messageNumber.get()+1; i<=messageCount; i++) {
					Message message = inbox.getMessage(i);
					lastPosition.setUid(inbox.getUID(message));
					logger.trace("Processing inbox messge (subject: {}, uid: {}, seq: {})", 
							message.getSubject(), lastPosition.getUid(), i);
					try {
						listener.onReceived(message);
					} catch (Exception e) {
						try {
							String[] fromHeader = message.getHeader("From");
							if (fromHeader != null && fromHeader.length != 0 
									&& !fromHeader[0].equalsIgnoreCase(checkSetting.getCheckAddress())) {
								InternetAddress from = InternetAddress.parse(fromHeader[0], true)[0];
								
								String template = StringUtils.join(settingManager.getEmailTemplates().getServiceDeskIssueOpenFailed(), "\n");
								Map<String, Object> bindings = new HashMap<>();
								bindings.put("exception", e);
								
								String htmlBody = EmailTemplates.evalTemplate(true, template, bindings);
								String textBody = EmailTemplates.evalTemplate(false, template, bindings);
								
								sendMailAsync(Lists.newArrayList(from.getAddress()), new ArrayList<>(), 
										new ArrayList<>(), "OneDev is unable to process your message", 
										htmlBody, textBody, null, null, null);								
							}
						} catch (Exception e2) {
							logger.error("Error sending mail", e);
						}
						logger.error("Error processing message", e);
					} 
				}
				messageNumber.set(messageCount);
			}
			
			private long getUid(IMAPFolder inbox, int messageNumber) throws MessagingException {
				if (messageNumber != 0)
					return inbox.getUID(inbox.getMessage(messageNumber));
				else
					return -1;
			}
			
			@Override
			public void run() {
		        Properties properties = new Properties();
		        
		        properties.setProperty("mail.imap.host", checkSetting.getImapHost());
				checkSetting.getSslSetting().configure(properties);
		        properties.setProperty("mail.imap.connectiontimeout", String.valueOf(Bootstrap.SOCKET_CONNECT_TIMEOUT));
		        properties.setProperty("mail.imap.timeout", String.valueOf(checkSetting.getTimeout()*1000));
	        	if (checkSetting.getImapCredential() instanceof OAuthAccessToken)
	        		properties.setProperty("mail.imap.auth.mechanisms", "XOAUTH2");
				
				Session session = Session.getInstance(properties);
				Store store = null;
				IMAPFolder inbox = null;
				try {
					store = session.getStore("imap");
					String credentialValue = checkSetting.getImapCredential().getValue();
					store.connect(checkSetting.getImapUser(), credentialValue);
					inbox = (IMAPFolder) store.getFolder("INBOX");
					inbox.open(Folder.READ_ONLY);
					
					long uidValidity = inbox.getUIDValidity();
					AtomicInteger messageNumber = new AtomicInteger(0);
					if (uidValidity == lastPosition.getUidValidity()) {
						logger.trace("Inbox uid validity unchanged (uid: {})", lastPosition.getUid());
						if (lastPosition.getUid() != -1) {
							Message lastMessage = inbox.getMessageByUID(lastPosition.getUid());
							if (lastMessage != null) {
								logger.trace("Last processed inbox message found (subject: {}, uid: {}, seq: {})", 
										lastMessage.getSubject(), lastPosition.getUid(), lastMessage.getMessageNumber());
								messageNumber.set(lastMessage.getMessageNumber());
								processMessages(inbox, messageNumber);
							} else {
								messageNumber.set(inbox.getMessageCount());
								lastPosition.setUid(getUid(inbox, messageNumber.get()));
								logger.trace("Last processed inbox message not found (uid reset to: {})", lastPosition.getUid());
							}
						} else {
							processMessages(inbox, messageNumber);
						}
					} else {
						lastPosition.setUidValidity(uidValidity);
						if (testMode)
							messageNumber.set(inbox.getMessageCount() - 5);
						else
							messageNumber.set(inbox.getMessageCount());							
						if (messageNumber.get() < 0)
							messageNumber.set(0);
						lastPosition.setUid(getUid(inbox, messageNumber.get()));
						logger.trace("Inbox uid validity changed (uid reset to: {})", lastPosition.getUid());
					}

					long time = System.currentTimeMillis();
					while (true) { 
						Thread.sleep(checkSetting.getPollInterval()*1000);
						processMessages(inbox, messageNumber);
						
						// discard inbox periodically to save memory
						if (System.currentTimeMillis()-time > MAX_INBOX_LIFE*1000)
							break;
					}
					
				} catch (Exception e) {
					throw ExceptionUtils.unchecked(e);
				} finally {
					if (inbox != null && inbox.isOpen()) {
						try {
							inbox.close(false);
						} catch (Exception ignored) {
						}
					}
					if (store != null) {
						try {
							store.close();
						} catch (Exception ignored) {
						}
					}
				}
			}
		});
	}
	
	@Override
	public String getReplyAddress(Issue issue) {
		MailSetting mailSetting = settingManager.getMailSetting();
		MailCheckSetting checkSetting = mailSetting!=null? mailSetting.getCheckSetting(): null;
		if (checkSetting != null) {
			ParsedEmailAddress checkAddress = ParsedEmailAddress.parse(checkSetting.getCheckAddress());
			return checkAddress.getSubAddressed("issue~" + issue.getId()); 
		} else {
			return null;
		}
	}
	
	@Override
	public String getReplyAddress(PullRequest request) {
		MailSetting mailSetting = settingManager.getMailSetting();
		MailCheckSetting checkSetting = mailSetting!=null? mailSetting.getCheckSetting(): null;
		if (checkSetting != null) {
			ParsedEmailAddress checkAddress = ParsedEmailAddress.parse(checkSetting.getCheckAddress());
			return checkAddress.getSubAddressed("pullrequest~" + request.getId()); 
		} else {
			return null;
		}
	}
	
	@Override
	public String getUnsubscribeAddress(Issue issue) {
		MailSetting mailSetting = settingManager.getMailSetting();
		MailCheckSetting checkSetting = mailSetting!=null? mailSetting.getCheckSetting(): null;
		if (checkSetting != null) {
			ParsedEmailAddress checkAddress = ParsedEmailAddress.parse(checkSetting.getCheckAddress());
			return checkAddress.getSubAddressed("issueunsubscribe~" + issue.getId()); 
		} else {
			return null;
		}
	}

	@Override
	public String getUnsubscribeAddress(PullRequest request) {
		MailSetting mailSetting = settingManager.getMailSetting();
		MailCheckSetting checkSetting = mailSetting!=null? mailSetting.getCheckSetting(): null;
		if (checkSetting != null) {
			ParsedEmailAddress checkAddress = ParsedEmailAddress.parse(checkSetting.getCheckAddress());
			return checkAddress.getSubAddressed("pullrequestunsubscribe~" + request.getId()); 
		} else {
			return null;
		}
	}
	
	private String getText(Project project, String attachmentGroup, Message message) 
			throws IOException, MessagingException {
		Attachments attachments = new Attachments();
		fillAttachments(project, attachmentGroup, message, attachments);
		String text = getText(project, attachmentGroup, message, attachments);

		attachments.identifiable.keySet().removeAll(attachments.referenced);
		attachments.nonIdentifiable.addAll(attachments.identifiable.values());
		if (!attachments.nonIdentifiable.isEmpty()) {
			text += "\n\n---";
			List<String> markdowns = new ArrayList<>();
			for (Attachment attachment: attachments.nonIdentifiable)
				markdowns.add(attachment.getMarkdown());
			text += "\n\n" + Joiner.on(" &nbsp;&nbsp;&nbsp;&bull;&nbsp;&nbsp;&nbsp; ").join(markdowns);
		}
		return text;
	}
	
	private String getText(Project project, String attachmentGroup, Part part, Attachments attachments) 
			throws IOException, MessagingException {
		if (part.getDisposition() == null) {
		    if (part.isMimeType("text/plain")) {
		        return HtmlEscape.escapeHtml5(part.getContent().toString());
		    } else if (part.isMimeType("text/html")) {
		        Document doc = Jsoup.parse(part.getContent().toString());
		        for (Element element: doc.getElementsByTag("img")) {
		        	String src = element.attr("src");
		        	if (src != null && src.startsWith("cid:")) {
		        		String contentId = "<" + src.substring("cid:".length()) + ">";
		        		attachments.referenced.add(contentId);
		        		Attachment attachment = attachments.identifiable.get(contentId);
		        		if (attachment != null) 
		        			element.attr("src", attachment.url);
		        	}
		        }
		        return doc.html();
		    } else if (part.isMimeType("multipart/*")) {
		    	Multipart multipart = (Multipart) part.getContent();
			    int count = multipart.getCount();
			    if (count != 0) {
				    boolean multipartAlt = new ContentType(multipart.getContentType()).match("multipart/alternative");
				    if (multipartAlt)
				        // alternatives appear in an order of increasing 
				        // faithfulness to the original content. Customize as req'd.
				        return getText(project, attachmentGroup, multipart.getBodyPart(count - 1), attachments);
				    StringBuilder builder = new StringBuilder();
				    for (int i=0; i<count; i++)  
				        builder.append(getText(project, attachmentGroup, multipart.getBodyPart(i), attachments));
				    return builder.toString();
			    } else {
			    	return "";
			    }
		    } else { 
		    	return "";
		    }
		} else {
			return "";
		}
	}
	
	private void fillAttachments(Project project, String attachmentGroup, Part part, Attachments attachments) 
			throws IOException, MessagingException {
	    if (part.getDisposition() != null) {
	    	String[] contentId = part.getHeader("Content-ID");
	    	String fileName = MimeUtility.decodeText(part.getFileName());
	        String attachmentName = attachmentManager.saveAttachment(project.getId(), attachmentGroup, 
					fileName, part.getInputStream());
			String attachmentUrl = project.getAttachmentUrlPath(attachmentGroup, attachmentName);
			Attachment attachment;
	        if (part.isMimeType("image/*"))
	        	attachment = new ImageAttachment(attachmentUrl, fileName);
	        else
	        	attachment = new FileAttachment(attachmentUrl, fileName);
			if (contentId != null && contentId.length != 0) 
				attachments.identifiable.put(contentId[0], attachment);
			else 
				attachments.nonIdentifiable.add(attachment);
	    } else if (part.isMimeType("multipart/*")) {
	    	Multipart multipart = (Multipart) part.getContent();
		    int count = multipart.getCount();
		    if (count != 0) {
			    boolean multipartAlt = new ContentType(multipart.getContentType()).match("multipart/alternative");
			    if (multipartAlt)
			        // alternatives appear in an order of increasing 
			        // faithfulness to the original content. Customize as req'd.
			        fillAttachments(project, attachmentGroup, multipart.getBodyPart(count - 1), attachments);
			    for (int i=0; i<count; i++)  
			        fillAttachments(project, attachmentGroup, multipart.getBodyPart(i), attachments);
		    }
	    } 
	}
	
	private static class Attachments {
		
		final Map<String, Attachment> identifiable = new LinkedHashMap<>();
		
		final Collection<Attachment> nonIdentifiable = new ArrayList<>();
		
		final Collection<String> referenced = new HashSet<>();

	}
	
	private static abstract class Attachment {
		
		final String url;
		
		final String fileName;
		
		public Attachment(String url, String fileName) {
			this.url = url;
			this.fileName = fileName;
		}

		public abstract String getMarkdown();
		
	}
	
	@Override
	public String toPlainText(String mailContent) {
		OutputSettings outputSettings = new OutputSettings();
		outputSettings.prettyPrint(false);
		String plainText = Jsoup.clean(mailContent, "", Safelist.none(), outputSettings);
		plainText = Joiner.on('\n').join(Splitter.on('\n').trimResults().split(plainText));
		plainText = plainText.replaceAll("\n\n(\n)+", "\n\n").trim();
		return plainText;
	}

	@Override
	public boolean isMailContent(String comment) {
		return comment.contains(String.format("<div class='%s'>", COMMENT_MARKER));
	}

	private static class ImageAttachment extends Attachment {

		public ImageAttachment(String url, String fileName) {
			super(url, fileName);
		}

		@Override
		public String getMarkdown() {
			return String.format("![%s](%s)", fileName, url);
		}
		
	}
	
	private static class FileAttachment extends Attachment {

		public FileAttachment(String url, String fileName) {
			super(url, fileName);
		}
		
		@Override
		public String getMarkdown() {
			return String.format("[%s](%s)", fileName, url);
		}
		
	}

}

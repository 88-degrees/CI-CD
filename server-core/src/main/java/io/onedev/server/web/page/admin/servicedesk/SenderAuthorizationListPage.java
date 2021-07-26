package io.onedev.server.web.page.admin.servicedesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.extensions.markup.html.repeater.data.grid.ICellPopulator;
import org.apache.wicket.extensions.markup.html.repeater.data.table.AbstractColumn;
import org.apache.wicket.extensions.markup.html.repeater.data.table.DataTable;
import org.apache.wicket.extensions.markup.html.repeater.data.table.HeadersToolbar;
import org.apache.wicket.extensions.markup.html.repeater.data.table.IColumn;
import org.apache.wicket.extensions.markup.html.repeater.data.table.NoRecordsToolbar;
import org.apache.wicket.markup.ComponentTag;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.list.LoopItem;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.data.IDataProvider;
import org.apache.wicket.markup.repeater.data.ListDataProvider;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.mapper.parameter.PageParameters;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.support.administration.SenderAuthorization;
import io.onedev.server.model.support.administration.ServiceDeskSetting;
import io.onedev.server.web.ajaxlistener.ConfirmClickListener;
import io.onedev.server.web.behavior.NoRecordsBehavior;
import io.onedev.server.web.behavior.sortable.SortBehavior;
import io.onedev.server.web.behavior.sortable.SortPosition;
import io.onedev.server.web.component.modal.ModalLink;
import io.onedev.server.web.component.modal.ModalPanel;
import io.onedev.server.web.component.svg.SpriteImage;
import io.onedev.server.web.page.admin.AdministrationPage;

@SuppressWarnings("serial")
public class SenderAuthorizationListPage extends AdministrationPage {

	private final List<SenderAuthorization> authorizations;
	
	public SenderAuthorizationListPage(PageParameters params) {
		super(params);
		authorizations = getSettingManager().getServiceDeskSetting().getSenderAuthorizations();
	}

	private DataTable<SenderAuthorization, Void> authorizationsTable;
	
	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		add(new ModalLink("addNew") {

			@Override
			protected Component newContent(String id, ModalPanel modal) {
				return new SenderAuthorizationEditPanel(id, -1) {

					@Override
					protected void onSave(AjaxRequestTarget target) {
						saveAuthorizations();
						target.add(authorizationsTable);
						modal.close();
					}

					@Override
					protected void onCancel(AjaxRequestTarget target) {
						modal.close();
					}

					@Override
					protected List<SenderAuthorization> getAuthorizations() {
						return authorizations;
					}

				};
			}

			@Override
			protected String getModalCssClass() {
				return "modal-lg";
			}
			
		});
		
		List<IColumn<SenderAuthorization, Void>> columns = new ArrayList<>();
		
		columns.add(new AbstractColumn<SenderAuthorization, Void>(Model.of("")) {

			@Override
			public void populateItem(Item<ICellPopulator<SenderAuthorization>> cellItem, String componentId, IModel<SenderAuthorization> rowModel) {
				cellItem.add(new SpriteImage(componentId, "grip") {

					@Override
					protected void onComponentTag(ComponentTag tag) {
						super.onComponentTag(tag);
						tag.setName("svg");
						tag.put("class", "icon drag-indicator");
					}
					
				});
			}
			
			@Override
			public String getCssClass() {
				return "minimum actions";
			}
			
		});		
		
		columns.add(new AbstractColumn<SenderAuthorization, Void>(Model.of("Applicable Senders")) {

			@Override
			public void populateItem(Item<ICellPopulator<SenderAuthorization>> cellItem, String componentId, IModel<SenderAuthorization> rowModel) {
				SenderAuthorization authorization = rowModel.getObject();
				if (authorization.getSenderEmails() != null)
					cellItem.add(new Label(componentId, authorization.getSenderEmails()));
				else
					cellItem.add(new Label(componentId, "<i>Any sender</i>").setEscapeModelStrings(false));
			}
			
		});		
		
		columns.add(new AbstractColumn<SenderAuthorization, Void>(Model.of("Authorized Projects")) {

			@Override
			public void populateItem(Item<ICellPopulator<SenderAuthorization>> cellItem, String componentId, IModel<SenderAuthorization> rowModel) {
				SenderAuthorization authorization = rowModel.getObject();
				if (authorization.getAuthorizedProjects() != null)
					cellItem.add(new Label(componentId, authorization.getAuthorizedProjects()));
				else
					cellItem.add(new Label(componentId, "<i>Any project</i>").setEscapeModelStrings(false));
			}
			
		});		
		
		columns.add(new AbstractColumn<SenderAuthorization, Void>(Model.of("Authorized Role")) {

			@Override
			public void populateItem(Item<ICellPopulator<SenderAuthorization>> cellItem, String componentId, IModel<SenderAuthorization> rowModel) {
				SenderAuthorization authorization = rowModel.getObject();
				cellItem.add(new Label(componentId, authorization.getAuthorizedRoleName()));
			}
			
		});		
		
		columns.add(new AbstractColumn<SenderAuthorization, Void>(Model.of("")) {

			@Override
			public void populateItem(Item<ICellPopulator<SenderAuthorization>> cellItem, String componentId, IModel<SenderAuthorization> rowModel) {
				int authorizationIndex = cellItem.findParent(LoopItem.class).getIndex();
				
				Fragment fragment = new Fragment(componentId, "actionColumnFrag", SenderAuthorizationListPage.this);
				fragment.add(new ModalLink("edit") {

					@Override
					protected Component newContent(String id, ModalPanel modal) {
						return new SenderAuthorizationEditPanel(id, authorizationIndex) {

							@Override
							protected void onSave(AjaxRequestTarget target) {
								saveAuthorizations();
								target.add(authorizationsTable);
								modal.close();
							}

							@Override
							protected void onCancel(AjaxRequestTarget target) {
								modal.close();
							}

							@Override
							protected List<SenderAuthorization> getAuthorizations() {
								return authorizations;
							}

						};
					}
					
					@Override
					protected String getModalCssClass() {
						return "modal-lg";
					}
					
				});
				fragment.add(new AjaxLink<Void>("delete") {

					@Override
					protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
						super.updateAjaxAttributes(attributes);
						attributes.getAjaxCallListeners().add(new ConfirmClickListener("Do you really want to delete this entry?"));
					}

					@Override
					public void onClick(AjaxRequestTarget target) {
						authorizations.remove(authorizationIndex);
						saveAuthorizations();
						target.add(authorizationsTable);
					}
					
				});
				
				cellItem.add(fragment);
			}

			@Override
			public String getCssClass() {
				return "actions";
			}

		});		
		
		IDataProvider<SenderAuthorization> dataProvider = new ListDataProvider<SenderAuthorization>() {

			@Override
			protected List<SenderAuthorization> getData() {
				return authorizations;
			}

		};
		
		add(authorizationsTable = new DataTable<SenderAuthorization, Void>("authorizations", columns, dataProvider, Integer.MAX_VALUE));
		authorizationsTable.addTopToolbar(new HeadersToolbar<Void>(authorizationsTable, null));
		authorizationsTable.addBottomToolbar(new NoRecordsToolbar(authorizationsTable));
		authorizationsTable.add(new NoRecordsBehavior());
		authorizationsTable.setOutputMarkupId(true);
		
		authorizationsTable.add(new SortBehavior() {

			@Override
			protected void onSort(AjaxRequestTarget target, SortPosition from, SortPosition to) {
				int fromIndex = from.getItemIndex();
				int toIndex = to.getItemIndex();
				if (fromIndex < toIndex) {
					for (int i=0; i<toIndex-fromIndex; i++) 
						Collections.swap(authorizations, fromIndex+i, fromIndex+i+1);
				} else {
					for (int i=0; i<fromIndex-toIndex; i++) 
						Collections.swap(authorizations, fromIndex-i, fromIndex-i-1);
				}
				saveAuthorizations();
				target.add(authorizationsTable);
			}
			
		}.sortable("tbody"));
	}
	
	@Override
	protected Component newTopbarTitle(String componentId) {
		return new Label(componentId, "Service Authorizations");
	}
	
	private SettingManager getSettingManager() {
		return OneDev.getInstance(SettingManager.class);
	}
	
	private void saveAuthorizations() {
		ServiceDeskSetting setting = getSettingManager().getServiceDeskSetting();
		setting.setSenderAuthorizations(authorizations);
		getSettingManager().saveServiceDeskSetting(setting);
	}
	
}